// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.googlesource.gerrit.plugins.replication.PushResultProcessing.NO_OP;
import static java.util.stream.Collectors.toList;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.common.ProjectInfo;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Test;

@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationIT extends LightweightPluginDaemonTest {
  private static final Optional<String> ALL_PROJECTS = Optional.empty();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int TEST_REPLICATION_DELAY = 1;
  private static final int TEST_REPLICATION_RETRY = 1;
  private static final Duration TEST_TIMEOUT =
      Duration.ofSeconds((TEST_REPLICATION_DELAY + TEST_REPLICATION_RETRY * 60) + 1);

  @Inject private SitePaths sitePaths;
  @Inject private ProjectOperations projectOperations;
  @Inject private DynamicSet<ProjectDeletedListener> deletedListeners;
  private Path pluginDataDir;
  private Path gitPath;
  private Path storagePath;
  private FileBasedConfig config;
  private ReplicationConfig replicationConfig;
  private ReplicationTasksStorage tasksStorage;

  @Override
  public void setUpTestPlugin() throws Exception {
    gitPath = sitePaths.site_path.resolve("git");

    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    setReplicationDestination(
        "remote1",
        "suffix1",
        Optional.of("not-used-project")); // Simulates a full replication.config initialization
    config.save();

    super.setUpTestPlugin();

    pluginDataDir = plugin.getSysInjector().getInstance(Key.get(Path.class, PluginData.class));
    replicationConfig = plugin.getSysInjector().getInstance(ReplicationConfig.class);
    storagePath = pluginDataDir.resolve("ref-updates");
    tasksStorage = plugin.getSysInjector().getInstance(ReplicationTasksStorage.class);
    cleanupReplicationTasks();
    tasksStorage.disableDeleteForTesting(true);
  }

  @Test
  public void shouldReplicateNewProject() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey sourceProject = createTestProject("foo");

    assertThat(listReplicationTasks("refs/meta/config")).hasSize(1);

    waitUntil(() -> nonEmptyProjectExists(Project.nameKey(sourceProject + "replica.git")));

    ProjectInfo replicaProject = gApi.projects().name(sourceProject + "replica").get();
    assertThat(replicaProject).isNotNull();
  }

  @Test
  public void shouldReplicateProjectDeletion() throws Exception {
    String projectNameDeleted = "project-deleted";
    Project.NameKey replicaProject = createTestProject(projectNameDeleted + "replica");
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    setProjectDeletionReplication("foo", true);
    reloadConfig();

    ProjectDeletedListener.Event event =
        new ProjectDeletedListener.Event() {
          @Override
          public String getProjectName() {
            return projectNameDeleted;
          }

          @Override
          public NotifyHandling getNotify() {
            return NotifyHandling.NONE;
          }
        };

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(event);
    }

    waitUntil(() -> !nonEmptyProjectExists(replicaProject));
  }

  @Test
  public void shouldReplicateNewChangeRef() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    assertThat(listReplicationTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(1);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldReplicateNewBranch() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    assertThat(listReplicationTasks("refs/heads/(mybranch|master)")).hasSize(2);

    try (Repository repo = repoManager.openRepository(targetProject);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref masterRef = getRef(sourceRepo, master);
      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(masterRef.getObjectId());
    }
  }

  @Test
  public void shouldReplicateNewBranchToTwoRemotes() throws Exception {
    Project.NameKey targetProject1 = createTestProject(project + "replica1");
    Project.NameKey targetProject2 = createTestProject(project + "replica2");

    setReplicationDestination("foo1", "replica1", ALL_PROJECTS);
    setReplicationDestination("foo2", "replica2", ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    assertThat(listReplicationTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(2);

    try (Repository repo1 = repoManager.openRepository(targetProject1);
        Repository repo2 = repoManager.openRepository(targetProject2)) {
      waitUntil(
          () ->
              (checkedGetRef(repo1, sourceRef) != null && checkedGetRef(repo2, sourceRef) != null));

      Ref targetBranchRef1 = getRef(repo1, sourceRef);
      assertThat(targetBranchRef1).isNotNull();
      assertThat(targetBranchRef1.getObjectId()).isEqualTo(sourceCommit.getId());

      Ref targetBranchRef2 = getRef(repo2, sourceRef);
      assertThat(targetBranchRef2).isNotNull();
      assertThat(targetBranchRef2.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldCreateIndividualReplicationTasksForEveryRemoteUrlPair() throws Exception {
    List<String> replicaSuffixes = Arrays.asList("replica1", "replica2");
    createTestProject(project + "replica1");
    createTestProject(project + "replica2");

    setReplicationDestination("foo1", replicaSuffixes, ALL_PROJECTS);
    setReplicationDestination("foo2", replicaSuffixes, ALL_PROJECTS);
    config.setInt("remote", "foo1", "replicationDelay", TEST_REPLICATION_DELAY * 100);
    config.setInt("remote", "foo2", "replicationDelay", TEST_REPLICATION_DELAY * 100);
    reloadConfig();

    createChange();

    assertThat(listReplicationTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(4);

    setReplicationDestination("foo1", replicaSuffixes, ALL_PROJECTS);
    setReplicationDestination("foo2", replicaSuffixes, ALL_PROJECTS);
  }

  @Test
  public void shouldCreateOneReplicationTaskWhenSchedulingRepoFullSync() throws Exception {
    createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, null, new ReplicationState(NO_OP), true);

    assertThat(listReplicationTasks(".*all.*")).hasSize(1);
  }

  @Test
  public void shouldMatchTemplatedURL() throws Exception {
    createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    String urlMatch = gitPath.resolve("${name}" + "replica" + ".git").toString();
    String expectedURI = gitPath.resolve(project + "replica" + ".git").toString();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), true);

    assertThat(listReplicationTasks(".*all.*")).hasSize(1);
    for (ReplicationTasksStorage.ReplicateRefUpdate task : tasksStorage.list()) {
      assertThat(task.uri).isEqualTo(expectedURI);
    }
  }

  @Test
  public void shouldMatchRealURL() throws Exception {
    createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    String urlMatch = gitPath.resolve(project + "replica" + ".git").toString();
    String expectedURI = urlMatch;

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), true);

    assertThat(listReplicationTasks(".*")).hasSize(1);
    for (ReplicationTasksStorage.ReplicateRefUpdate task : tasksStorage.list()) {
      assertThat(task.uri).isEqualTo(expectedURI);
    }
    assertThat(tasksStorage.list()).isNotEmpty();
  }

  @Test
  public void shouldReplicateHeadUpdate() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
    String newHead = "refs/heads/newhead";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newHead).create(input);
    gApi.projects().name(project.get()).head(newHead);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, newHead) != null);

      Ref targetProjectHead = getRef(repo, Constants.HEAD);
      assertThat(targetProjectHead).isNotNull();
      assertThat(targetProjectHead.getTarget().getName()).isEqualTo(newHead);
    }
  }

  @Test
  public void shouldNotDrainTheQueueWhenReloading() throws Exception {
    // Setup repo to replicate
    Project.NameKey targetProject = createTestProject(project + "replica");
    String remoteName = "doNotDrainQueue";
    setReplicationDestination(remoteName, "replica", ALL_PROJECTS);

    Result pushResult = createChange();
    shutdownDestinations();

    pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    assertThrows(
        InterruptedException.class,
        () -> {
          try (Repository repo = repoManager.openRepository(targetProject)) {
            waitUntil(() -> checkedGetRef(repo, sourceRef) != null);
          }
        });
  }

  @Test
  public void shouldDrainTheQueueWhenReloading() throws Exception {
    // Setup repo to replicate
    Project.NameKey targetProject = createTestProject(project + "replica");
    String remoteName = "drainQueue";
    setReplicationDestination(remoteName, "replica", ALL_PROJECTS);

    config.setInt("remote", remoteName, "drainQueueAttempts", 2);
    config.save();
    reloadConfig();

    Result pushResult = createChange();
    shutdownDestinations();

    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);
      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldNotDropEventsWhenStarting() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    replicationQueueStop();
    Result pushResult = createChange();
    replicationQueueStart();

    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);
      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldCleanupTasksAfterNewProjectReplication() throws Exception {
    tasksStorage.disableDeleteForTesting(false);
    setReplicationDestination("task_cleanup_project", "replica", ALL_PROJECTS);
    config.setInt("remote", "task_cleanup_project", "replicationRetry", 0);
    config.save();
    reloadConfig();
    assertThat(tasksStorage.listRunning()).hasSize(0);
    Project.NameKey sourceProject = createTestProject("task_cleanup_project");

    waitUntil(() -> nonEmptyProjectExists(Project.nameKey(sourceProject + "replica.git")));
    waitUntil(() -> tasksStorage.listRunning().size() == 0);
  }

  @Test
  public void shouldCleanupBothTasksAndLocksAfterNewProjectReplication() throws Exception {
    tasksStorage.disableDeleteForTesting(false);
    setReplicationDestination("task_cleanup_locks_project", "replica", ALL_PROJECTS);
    config.setInt("remote", "task_cleanup_locks_project", "replicationRetry", 0);
    config.save();
    reloadConfig();
    assertThat(tasksStorage.listRunning()).hasSize(0);
    Project.NameKey sourceProject = createTestProject("task_cleanup_locks_project");

    waitUntil(() -> nonEmptyProjectExists(Project.nameKey(sourceProject + "replica.git")));
    waitUntil(() -> isTaskCleanedUp());
  }

  private Ref getRef(Repository repo, String branchName) throws IOException {
    return repo.getRefDatabase().exactRef(branchName);
  }

  private Ref checkedGetRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().exactRef(branchName);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in repo %s", branchName, repo);
      return null;
    }
  }

  private void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project) throws IOException {
    setReplicationDestination(remoteName, Arrays.asList(replicaSuffix), project);
  }

  private void setReplicationDestination(
      String remoteName, List<String> replicaSuffixes, Optional<String> project)
      throws IOException {

    List<String> replicaUrls =
        replicaSuffixes.stream()
            .map(suffix -> gitPath.resolve("${name}" + suffix + ".git").toString())
            .collect(toList());
    config.setStringList("remote", remoteName, "url", replicaUrls);
    config.setInt("remote", remoteName, "replicationDelay", TEST_REPLICATION_DELAY);
    config.setInt("remote", remoteName, "replicationRetry", TEST_REPLICATION_RETRY);
    project.ifPresent(prj -> config.setString("remote", remoteName, "projects", prj));
    config.setBoolean("gerrit", null, "autoReload", true);
    config.save();
  }

  private void setProjectDeletionReplication(String remoteName, boolean replicateProjectDeletion)
      throws IOException {
    config.setBoolean("remote", remoteName, "replicateProjectDeletions", replicateProjectDeletion);
    config.save();
  }

  private void waitUntil(Supplier<Boolean> waitCondition) throws InterruptedException {
    WaitUtil.waitUntil(waitCondition, TEST_TIMEOUT);
  }

  private void reloadConfig() {
    getAutoReloadConfigDecoratorInstance().reload();
  }

  private void shutdownDestinations() {
    getInstance(DestinationsCollection.class).shutdown();
  }

  private void replicationQueueStart() {
    getReplicationQueueInstance().start();
  }

  private void replicationQueueStop() {
    getReplicationQueueInstance().stop();
  }

  private AutoReloadConfigDecorator getAutoReloadConfigDecoratorInstance() {
    return getInstance(AutoReloadConfigDecorator.class);
  }

  private ReplicationQueue getReplicationQueueInstance() {
    return getInstance(ReplicationQueue.class);
  }

  private <T> T getInstance(Class<T> classObj) {
    return plugin.getSysInjector().getInstance(classObj);
  }

  private Project.NameKey createTestProject(String name) throws Exception {
    return projectOperations.newProject().name(name).create();
  }

  private List<ReplicateRefUpdate> listReplicationTasks(String refRegex) {
    Pattern refmaskPattern = Pattern.compile(refRegex);
    return tasksStorage.list().stream()
        .filter(task -> refmaskPattern.matcher(task.ref).matches())
        .collect(toList());
  }

  public void cleanupReplicationTasks() throws IOException {
    cleanupReplicationTasks(storagePath);
  }

  private void cleanupReplicationTasks(Path basePath) throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(basePath)) {
      for (Path path : files) {
        if (Files.isDirectory(path)) {
          cleanupReplicationTasks(path);
        } else {
          path.toFile().delete();
        }
      }
    }
  }

  private boolean isTaskCleanedUp() {
    Path refUpdates = replicationConfig.getEventsDirectory().resolve("ref-updates");
    Path runningUpdates = refUpdates.resolve("running");
    try {
      return Files.list(runningUpdates).count() == 0;
    } catch (IOException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  private boolean nonEmptyProjectExists(Project.NameKey name) {
    try (Repository r = repoManager.openRepository(name)) {
      return !r.getAllRefsByPeeledObjectId().isEmpty();
    } catch (Exception e) {
      return false;
    }
  }
}
