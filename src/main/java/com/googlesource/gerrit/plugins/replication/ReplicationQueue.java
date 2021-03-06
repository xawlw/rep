// Copyright (C) 2009 The Android Open Source Project
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

import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Queues;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.util.logging.NamedFluentLogger;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.eclipse.jgit.transport.URIish;

/** Manages automatic replication to remote repositories. */
public class ReplicationQueue
    implements ObservableQueue,
        LifecycleListener,
        GitReferenceUpdatedListener,
        ProjectDeletedListener,
        HeadUpdatedListener {
  static final String REPLICATION_LOG_NAME = "replication_log";
  static final NamedFluentLogger repLog = NamedFluentLogger.forName(REPLICATION_LOG_NAME);

  private final ReplicationStateListener stateLog;

  private final ReplicationConfig replConfig;
  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final Provider<ReplicationDestinations> destinations; // For Guice circular dependency
  private final ReplicationTasksStorage replicationTasksStorage;
  private volatile boolean running;
  private volatile boolean replaying;
  private final Queue<ReferenceUpdatedEvent> beforeStartupEventsQueue;
  private Distributor distributor;

  @Inject
  ReplicationQueue(
      ReplicationConfig rc,
      WorkQueue wq,
      Provider<ReplicationDestinations> rd,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListeners sl,
      ReplicationTasksStorage rts) {
    replConfig = rc;
    workQueue = wq;
    dispatcher = dis;
    destinations = rd;
    stateLog = sl;
    replicationTasksStorage = rts;
    beforeStartupEventsQueue = Queues.newConcurrentLinkedQueue();
  }

  @Override
  public void start() {
    if (!running) {
      destinations.get().startup(workQueue);
      running = true;
      replicationTasksStorage.resetAll();
      firePendingEvents();
      fireBeforeStartupEvents();
      distributor = new Distributor(workQueue);
    }
  }

  @Override
  public void stop() {
    running = false;
    distributor.stop();
    int discarded = destinations.get().shutdown();
    if (discarded > 0) {
      repLog.atWarning().log("Canceled %d replication events during shutdown", discarded);
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isReplaying() {
    return replaying;
  }

  void scheduleFullSync(Project.NameKey project, String urlMatch, ReplicationState state) {
    scheduleFullSync(project, urlMatch, state, false);
  }

  @VisibleForTesting
  public void scheduleFullSync(
      Project.NameKey project, String urlMatch, ReplicationState state, boolean now) {
    fire(project, urlMatch, PushOne.ALL_REFS, state, now, false);
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    fire(event.getProjectName(), event.getRefName(), false);
  }

  private void fire(String projectName, String refName, boolean isPersisted) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    fire(Project.nameKey(projectName), null, refName, state, false, isPersisted);
    state.markAllPushTasksScheduled();
  }

  private void fire(
      Project.NameKey project,
      String urlMatch,
      String refName,
      ReplicationState state,
      boolean now,
      boolean isPersisted) {
    if (!running) {
      stateLog.warn(
          "Replication plugin did not finish startup before event, event replication is postponed",
          state);
      beforeStartupEventsQueue.add(ReferenceUpdatedEvent.create(project.get(), refName));
      return;
    }

    for (Destination cfg : destinations.get().getAll(FilterType.ALL)) {
      if (cfg.wouldPushProject(project) && cfg.wouldPushRef(refName)) {
        for (URIish uri : cfg.getURIs(project, urlMatch)) {
          if (!isPersisted) {
            replicationTasksStorage.create(
                new ReplicateRefUpdate(project.get(), refName, uri, cfg.getRemoteConfigName()));
          }
          cfg.schedule(project, refName, uri, state, now);
        }
      } else {
        repLog.atFine().log("Skipping ref %s on project %s", refName, project.get());
      }
    }
  }

  private void firePendingEvents() {
    replaying = true;
    try {
      Set<String> eventsReplayed = new HashSet<>();
      replaying = true;
      for (ReplicationTasksStorage.ReplicateRefUpdate t : replicationTasksStorage.listWaiting()) {
        String eventKey = String.format("%s:%s", t.project, t.ref);
        if (!eventsReplayed.contains(eventKey)) {
          repLog.atInfo().log("Firing pending task %s", eventKey);
          fire(t.project, t.ref, true);
          eventsReplayed.add(eventKey);
        }
      }
    } finally {
      replaying = false;
    }
  }

  private void pruneCompleted() {
    // Queue tasks have wrappers around them so workQueue.getTasks() does not return the PushOnes.
    // We also cannot access them by taskId since PushOnes don't have a taskId, they do have
    // and Id, but it not the id assigned to the task in the queues. The tasks in the queue
    // do use the same name as returned by toString() though, so that be used to correlate
    // PushOnes with queue tasks despite their wrappers.
    Set<String> prunableTaskNames = new HashSet<>();
    for (Destination destination : destinations.get().getAll(FilterType.ALL)) {
      prunableTaskNames.addAll(destination.getPrunableTaskNames());
    }

    for (WorkQueue.Task<?> task : workQueue.getTasks()) {
      WorkQueue.Task.State state = task.getState();
      if (state == WorkQueue.Task.State.SLEEPING || state == WorkQueue.Task.State.READY) {
        if (task instanceof WorkQueue.ProjectTask) {
          if (prunableTaskNames.contains(task.toString())) {
            repLog.atFine().log("Pruning externally completed task: %s", task);
            task.cancel(false);
          }
        }
      }
    }
  }

  @Override
  public void onProjectDeleted(ProjectDeletedListener.Event event) {
    Project.NameKey p = Project.nameKey(event.getProjectName());
    destinations.get().getURIs(Optional.empty(), p, FilterType.PROJECT_DELETION).entries().stream()
        .forEach(e -> e.getKey().scheduleDeleteProject(e.getValue(), p));
  }

  @Override
  public void onHeadUpdated(HeadUpdatedListener.Event event) {
    Project.NameKey p = Project.nameKey(event.getProjectName());
    destinations.get().getURIs(Optional.empty(), p, FilterType.ALL).entries().stream()
        .forEach(e -> e.getKey().scheduleUpdateHead(e.getValue(), p, event.getNewHeadName()));
  }

  private void fireBeforeStartupEvents() {
    Set<String> eventsReplayed = new HashSet<>();
    for (ReferenceUpdatedEvent event : beforeStartupEventsQueue) {
      String eventKey = String.format("%s:%s", event.projectName(), event.refName());
      if (!eventsReplayed.contains(eventKey)) {
        repLog.atInfo().log("Firing pending task %s", event);
        fire(event.projectName(), event.refName(), false);
        eventsReplayed.add(eventKey);
      }
    }
  }

  @AutoValue
  abstract static class ReferenceUpdatedEvent {

    static ReferenceUpdatedEvent create(String projectName, String refName) {
      return new AutoValue_ReplicationQueue_ReferenceUpdatedEvent(projectName, refName);
    }

    public abstract String projectName();

    public abstract String refName();
  }

  protected class Distributor implements WorkQueue.CancelableRunnable {
    public ScheduledThreadPoolExecutor executor;
    public ScheduledFuture<?> future;

    public Distributor(WorkQueue wq) {
      int distributionInterval = replConfig.getDistributionInterval();
      if (distributionInterval > 0) {
        executor = wq.createQueue(1, "Replication Distribution", false);
        future =
            executor.scheduleWithFixedDelay(
                this, distributionInterval, distributionInterval, SECONDS);
      }
    }

    @Override
    public void run() {
      if (!running) {
        return;
      }
      try {
        firePendingEvents();
        pruneCompleted();
      } catch (Exception e) {
        repLog.atSevere().withCause(e).log("error distributing tasks");
      }
    }

    @Override
    public void cancel() {
      future.cancel(true);
    }

    public void stop() {
      if (executor != null) {
        cancel();
        executor.getQueue().remove(this);
      }
    }

    @Override
    public String toString() {
      return "Replication Distributor";
    }
  }
}
