// Copyright (C) 2018 The Android Open Source Project
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;

/**
 * A persistent store for replication tasks.
 *
 * <p>The data of this store lives under <replication_data>/ref-updates where replication_data is
 * determined by the replication.eventsDirectory config option and defaults to
 * <site_dir>/data/replication. Atomic renames must be supported from anywhere within the store to
 * anywhere within the store. This generally means that all the contents of the store needs to live
 * on the same filesystem.
 *
 * <p>Individual tasks are stored in files under the following directories using the sha1 of the
 * task:
 *
 * <p><code>
 *   .../building/<tmp_name>                       new replication tasks under construction
 *   .../running/<uri_sha1>/                       lock for URI
 *   .../running/<uri_sha1>/<task_sha1>            running replication tasks
 *   .../waiting/<task_sha1_NN_shard>/<task_sha1>  outstanding replication tasks
 * </code>
 *
 * <p>The URI lock is acquired by creating the directory and released by removing it.
 *
 * <p>Tasks are moved atomically via a rename between those directories to indicate the current
 * state of each task.
 *
 * <p>Note: The .../waiting/<task_sha1_NN_shard> directories are never removed. This helps prevent
 * failures when moving tasks to and from the shard directories from different hosts concurrently.
 */
@Singleton
public class ReplicationTasksStorage {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private boolean disableDeleteForTesting;

  public static class ReplicateRefUpdate extends UriUpdate {
    public final String ref;

    public ReplicateRefUpdate(UriUpdate update, String ref) {
      this(update.project, ref, update.uri, update.remote);
    }

    public ReplicateRefUpdate(String project, String ref, URIish uri, String remote) {
      this(project, ref, uri.toASCIIString(), remote);
    }

    protected ReplicateRefUpdate(String project, String ref, String uri, String remote) {
      super(project, uri, remote);
      this.ref = ref;
    }

    @Override
    public String toString() {
      return "ref-update " + ref + " (" + super.toString() + ")";
    }
  }

  public static class UriUpdate {
    public final String project;
    public final String uri;
    public final String remote;

    public UriUpdate(PushOne push) {
      this(push.getProjectNameKey().get(), push.getURI(), push.getRemoteName());
    }

    public UriUpdate(String project, URIish uri, String remote) {
      this(project, uri.toASCIIString(), remote);
    }

    public UriUpdate(String project, String uri, String remote) {
      this.project = project;
      this.uri = uri;
      this.remote = remote;
    }

    @Override
    public String toString() {
      return "uri-update " + project + " uri:" + uri + " remote:" + remote;
    }
  }

  private static final Gson GSON = new Gson();

  private final Path refUpdates;
  private final Path buildingUpdates;
  private final Path runningUpdates;
  private final Path waitingUpdates;

  @Inject
  ReplicationTasksStorage(ReplicationConfig config) {
    refUpdates = config.getEventsDirectory().resolve("ref-updates");
    buildingUpdates = refUpdates.resolve("building");
    runningUpdates = refUpdates.resolve("running");
    waitingUpdates = refUpdates.resolve("waiting");
  }

  public synchronized String create(ReplicateRefUpdate r) {
    return new Task(r).create();
  }

  @VisibleForTesting
  public void disableDeleteForTesting(boolean deleteDisabled) {
    this.disableDeleteForTesting = deleteDisabled;
  }

  public synchronized boolean start(PushOne push) {
    UriLock lock = new UriLock(push);
    if (!lock.acquire()) {
      return false;
    }

    boolean started = false;
    for (String ref : push.getRefs()) {
      started = new Task(lock, ref).start() || started;
    }

    if (!started) { // No tasks left, likely replicated externally
      lock.release();
    }
    return started;
  }

  public synchronized void reset(PushOne push) {
    UriLock lock = new UriLock(push);
    for (String ref : push.getRefs()) {
      new Task(lock, ref).reset();
    }
    lock.release();
  }

  public synchronized void resetAll() {
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(createDir(runningUpdates))) {
      for (Path dir : dirs) {
        UriLock lock = null;
        try {
          for (ReplicateRefUpdate u : list(dir)) {
            if (lock == null) {
              lock = new UriLock(u);
            }
            new Task(u).reset();
          }
        } catch (DirectoryIteratorException d) {
          // iterating over the sub-directories is expected to have dirs disappear
          Nfs.throwIfNotStaleFileHandle(d.getCause());
        }
        if (lock != null) {
          lock.release();
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error while aborting running tasks");
    }
  }

  public boolean isWaiting(PushOne push) {
    return push.getRefs().stream().map(ref -> new Task(push, ref)).anyMatch(Task::isWaiting);
  }

  public void finish(PushOne push) {
    UriLock lock = new UriLock(push);
    for (ReplicateRefUpdate r : list(lock.runningDir)) {
      new Task(lock, r.ref).finish();
    }
    lock.release();
  }

  public synchronized List<ReplicateRefUpdate> listWaiting() {
    return list(createDir(waitingUpdates));
  }

  @VisibleForTesting
  public synchronized List<ReplicateRefUpdate> listRunning() {
    return list(createDir(runningUpdates));
  }

  @VisibleForTesting
  public synchronized List<ReplicateRefUpdate> listBuilding() {
    return list(createDir(buildingUpdates));
  }

  @VisibleForTesting
  public synchronized List<ReplicateRefUpdate> list() {
    return list(createDir(refUpdates));
  }

  private List<ReplicateRefUpdate> list(Path tasks) {
    List<ReplicateRefUpdate> results = new ArrayList<>();
    try (DirectoryStream<Path> events = Files.newDirectoryStream(tasks)) {
      for (Path path : events) {
        if (Files.isRegularFile(path)) {
          try {
            String json = new String(Files.readAllBytes(path), UTF_8);
            results.add(GSON.fromJson(json, ReplicateRefUpdate.class));
          } catch (NoSuchFileException ex) {
            logger.atFine().log(
                "File %s not found while listing waiting tasks (likely in-flight or completed by another node)",
                path);
          } catch (IOException e) {
            logger.atSevere().withCause(e).log("Error when firing pending event %s", path);
          }
        } else if (Files.isDirectory(path)) {
          try {
            results.addAll(list(path));
          } catch (DirectoryIteratorException d) {
            // iterating over the sub-directories is expected to have dirs disappear
            Nfs.throwIfNotStaleFileHandle(d.getCause());
          }
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error while listing tasks");
    }
    return results;
  }

  @SuppressWarnings("deprecation")
  private ObjectId sha1(String s) {
    return ObjectId.fromRaw(Hashing.sha1().hashString(s, UTF_8).asBytes());
  }

  private static Path createDir(Path dir) {
    try {
      return Files.createDirectories(dir);
    } catch (IOException e) {
      throw new ProvisionException(String.format("Couldn't create %s", dir), e);
    }
  }

  private class UriLock {
    public final UriUpdate update;
    public final String uriKey;
    public final Path runningDir;

    public UriLock(PushOne push) {
      this(new UriUpdate(push));
    }

    public UriLock(UriUpdate update) {
      this.update = update;
      uriKey = sha1(update.uri).name();
      runningDir = createDir(runningUpdates).resolve(uriKey);
    }

    public boolean acquire() {
      try {
        logger.atFine().log("MKDIR %s %s", runningDir, updateLog());
        Files.createDirectory(runningDir);
        return true;
      } catch (FileAlreadyExistsException e) {
        return false; // already running, likely externally
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while starting uri %s", uriKey);
        return true; // safer to risk a duplicate than to skip it
      }
    }

    public void release() {
      try {
        if (disableDeleteForTesting && Files.list(runningDir).findFirst().isPresent()) {
          logger.atFine().log("DELETE %s %s DISABLED", runningDir, updateLog());
          return;
        }

        logger.atFine().log("DELETE %s %s", runningDir, updateLog());
        Files.delete(runningDir);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while releasing uri %s", uriKey);
      }
    }

    private String updateLog() {
      return String.format("(%s => %s)", update.project, update.uri);
    }
  }

  private class Task {
    public final ReplicateRefUpdate update;
    public final String taskKey;
    public final Path running;
    public final Path waiting;

    public Task(ReplicateRefUpdate r) {
      this(new UriLock(r), r.ref);
    }

    public Task(PushOne push, String ref) {
      this(new UriLock(push), ref);
    }

    public Task(UriLock lock, String ref) {
      update = new ReplicateRefUpdate(lock.update, ref);
      String key = update.project + "\n" + update.ref + "\n" + update.uri + "\n" + update.remote;
      taskKey = sha1(key).name();
      running = lock.runningDir.resolve(taskKey);
      waiting = createDir(waitingUpdates.resolve(taskKey.substring(0, 2))).resolve(taskKey);
    }

    public String create() {
      if (Files.exists(waiting)) {
        return taskKey;
      }

      String json = GSON.toJson(update) + "\n";
      try {
        Path tmp = Files.createTempFile(createDir(buildingUpdates), taskKey, null);
        logger.atFine().log("CREATE %s %s", tmp, updateLog());
        Files.write(tmp, json.getBytes(UTF_8));
        logger.atFine().log("RENAME %s %s %s", tmp, waiting, updateLog());
        rename(tmp, waiting);
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Couldn't create task %s", json);
      }
      return taskKey;
    }

    public boolean start() {
      rename(waiting, running);
      return Files.exists(running);
    }

    public void reset() {
      rename(running, waiting);
    }

    public boolean isWaiting() {
      return Files.exists(waiting);
    }

    public void finish() {
      if (disableDeleteForTesting) {
        logger.atFine().log("DELETE %s %s DISABLED", running, updateLog());
        return;
      }

      try {
        logger.atFine().log("DELETE %s %s", running, updateLog());
        Files.delete(running);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while finishing task %s", taskKey);
      }
    }

    private void rename(Path from, Path to) {
      try {
        logger.atFine().log("RENAME %s to %s %s", from, to, updateLog());
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while renaming task %s", taskKey);
      }
    }

    private String updateLog() {
      return String.format("(%s:%s => %s)", update.project, update.ref, update.uri);
    }
  }
}
