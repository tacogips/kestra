package io.kestra.controller.grpc.services;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;

import io.kestra.core.models.executions.TaskRun;
import io.kestra.core.models.executions.TaskRunBroadcast;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.tasks.WorkerSelector;
import io.kestra.core.runners.WorkerTask;
import io.kestra.core.runners.WorkerTaskResult;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.worker.WorkerQueues;
import io.kestra.plugin.core.flow.WorkingDirectory;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Coordinates broadcast dispatch of a {@link WorkerTask} to every worker of a Worker Queue.
 * <p>
 * Broadcast is enabled by default for runnable tasks with a task-level worker selector. Set
 * {@code workerSelector.broadcast: false} for single-worker dispatch. The default and system
 * Worker Queues are always single-dispatch.
 * <p>
 * For a broadcast task, {@link WorkerJobDispatcher} asks this coordinator to fan the job
 * out into one per-worker copy ({@link #fanOut}), each pinned to a specific worker via
 * {@link TaskRunBroadcast} and carrying a fresh task run id. Results from the copies are fed
 * back through {@link #onResult}, which aggregates them and emits a single result for the
 * original task run once every copy reached a terminal state — so the executor never sees the
 * copies at all.
 * <p>
 * Aggregation state is local to this controller instance. Broadcast dispatch therefore requires
 * a single worker-controller. If the state is unexpectedly lost, a terminal copy result is
 * mapped back to the original task run and failed because successful aggregation can no longer
 * be proven.
 */
@Singleton
@Slf4j
public class BroadcastTaskCoordinator {

    private static final Duration COMPLETED_STATE_TTL = Duration.ofHours(24);

    /**
     * Aggregation state per original task run id.
     */
    private final Cache<String, BroadcastState> statesByOriginId;

    /**
     * Copy task run id to original task run id, used to remap logs and metrics. Kept after
     * aggregation completes so late-arriving log entries still map to the original task run.
     */
    private final Cache<String, CopyState> copyStatesByCopyId;

    /**
     * Copy ids whose aggregate was emitted successfully. This suppresses late or duplicate copy
     * results after the active aggregation state has been removed.
     */
    private final Cache<String, Boolean> completedCopyIds;

    /**
     * Creates a coordinator using the system clock for cache retention.
     */
    public BroadcastTaskCoordinator() {
        this(Ticker.systemTicker());
    }

    BroadcastTaskCoordinator(Ticker ticker) {
        statesByOriginId = Caffeine.newBuilder()
            .ticker(ticker)
            .expireAfter(new Expiry<String, BroadcastState>() {
                @Override
                public long expireAfterCreate(String key, BroadcastState state, long currentTime) {
                    return state.expiryNanos();
                }

                @Override
                public long expireAfterUpdate(String key, BroadcastState state, long currentTime, long currentDuration) {
                    return state.expiryNanos();
                }

                @Override
                public long expireAfterRead(String key, BroadcastState state, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();
        copyStatesByCopyId = Caffeine.newBuilder()
            .ticker(ticker)
            .expireAfter(new Expiry<String, CopyState>() {
                @Override
                public long expireAfterCreate(String key, CopyState state, long currentTime) {
                    return state.expiryNanos();
                }

                @Override
                public long expireAfterUpdate(String key, CopyState state, long currentTime, long currentDuration) {
                    return state.expiryNanos();
                }

                @Override
                public long expireAfterRead(String key, CopyState state, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();
        completedCopyIds = Caffeine.newBuilder()
            .ticker(ticker)
            .expireAfterWrite(COMPLETED_STATE_TTL)
            .build();
    }

    /**
     * Returns true when the given job is an original broadcast task that must be fanned out.
     * <p>
     * Broadcast is enabled by default and is skipped for:
     * <ul>
     * <li>the default and system Worker Queues (always single-dispatch);</li>
     * <li>per-worker copies of an already fanned-out broadcast task;</li>
     * <li>tasks without a task-level selector or with {@code broadcast: false};</li>
     * <li>{@link WorkingDirectory} tasks, which host child task runs.</li>
     * </ul>
     *
     * @param workerTask the worker task to inspect
     * @param workerQueueId the dispatch key of the Worker Queue (empty string for the default queue)
     */
    public boolean isBroadcastTask(WorkerTask workerTask, String workerQueueId) {
        if (
            workerQueueId == null || workerQueueId.isEmpty() || WorkerQueues.DEFAULT_ID.equals(workerQueueId)
                || WorkerQueues.SYSTEM_ID.equals(workerQueueId)
        ) {
            return false;
        }
        if (workerTask.getTaskRun() == null || workerTask.getTaskRun().getBroadcast() != null) {
            return false;
        }
        if (workerTask.getTask() == null) {
            return false;
        }
        if (workerTask.getTask() instanceof WorkingDirectory) {
            log.debug(
                "Skipping broadcast for WorkingDirectory task '{}': broadcast is only supported on runnable tasks",
                workerTask.getTask().getId()
            );
            return false;
        }
        WorkerSelector selector = workerTask.getTask().getWorkerSelector();
        return selector != null && selector.isBroadcastEnabled();
    }

    /**
     * Registers the fan-out of an original broadcast task to the given workers and returns
     * one pinned per-worker copy for each. Returns an empty list when a fan-out for the same
     * original task run is already registered (duplicate delivery).
     *
     * @param workerTask the original broadcast worker task
     * @param workerIds the ids of the workers to broadcast to (must be non-empty)
     */
    public List<WorkerTask> fanOut(WorkerTask workerTask, List<String> workerIds) {
        String originId = workerTask.getTaskRun().getId();

        BroadcastState state = new BroadcastState();
        List<WorkerTask> copies = new ArrayList<>(workerIds.size());
        for (String workerId : workerIds) {
            String copyId = IdUtils.create();
            state.workerIdByCopyId.put(copyId, workerId);

            TaskRun copyTaskRun = workerTask.getTaskRun().toBuilder()
                .id(copyId)
                .broadcast(new TaskRunBroadcast(originId, workerId))
                .build();
            copies.add(
                WorkerTask.builder()
                    .taskRun(copyTaskRun)
                    .task(workerTask.getTask())
                    .data(workerTask.getData())
                    .executionKind(workerTask.getExecutionKind())
                    .build()
            );
        }

        if (statesByOriginId.asMap().putIfAbsent(originId, state) != null) {
            log.warn("Duplicate broadcast fan-out for task run '{}', ignoring", originId);
            return List.of();
        }
        state.workerIdByCopyId.keySet().forEach(copyId -> copyStatesByCopyId.put(copyId, new CopyState(originId, false)));
        return copies;
    }

    /**
     * Handles a worker task result, transparently for non-broadcast results.
     * <ul>
     * <li>Results without a broadcast marker pass through unchanged.</li>
     * <li>The first RUNNING transition of any copy is mapped to the original task run and
     * forwarded so the execution shows progress; other non-terminal copy results are swallowed.</li>
     * <li>Terminal copy results are recorded; once every copy is terminal, a single aggregated
     * result for the original task run is returned (worst state wins, outputs merged per worker).</li>
     * <li>Copy results with no local aggregation state (controller restart, resubmission handled
     * elsewhere) are mapped to the original task run id and forwarded as-is.</li>
     * </ul>
     *
     * @param result an incoming worker task result
     * @return the result to emit to the worker task result queue, if any
     */
    public Optional<WorkerTaskResult> onResult(WorkerTaskResult result) {
        TaskRunBroadcast marker = result.getTaskRun().getBroadcast();
        if (marker == null) {
            return Optional.of(result);
        }

        String copyId = result.getTaskRun().getId();
        BroadcastState state = statesByOriginId.getIfPresent(marker.originTaskRunId());
        if (state == null) {
            if (completedCopyIds.getIfPresent(copyId) != null) {
                return Optional.empty();
            }
            log.warn(
                "No broadcast state for copy '{}' of task run '{}': failing the original task run because aggregation cannot be completed",
                copyId, marker.originTaskRunId()
            );
            return Optional.of(mapResultWithoutAggregationState(result, marker.originTaskRunId()));
        }

        synchronized (state) {
            if (!state.workerIdByCopyId.containsKey(copyId)) {
                log.warn(
                    "Ignoring stale broadcast copy '{}' that does not belong to the active aggregation for task run '{}'",
                    copyId, marker.originTaskRunId()
                );
                return Optional.empty();
            }

            if (state.emissionAcknowledged || (state.completed && state.emissionClaimed)) {
                return Optional.empty();
            }
            if (state.completed) {
                state.emissionClaimed = true;
                return Optional.of(state.aggregatedResult);
            }

            if (!result.getTaskRun().getState().isTerminated()) {
                if (State.Type.RUNNING.equals(result.getTaskRun().getState().getCurrent()) && !state.runningForwarded) {
                    state.runningForwarded = true;
                    return Optional.of(mapToOrigin(result, marker.originTaskRunId()));
                }
                return Optional.empty();
            }

            state.terminalResultsByCopyId.put(copyId, result);
            if (state.terminalResultsByCopyId.size() < state.workerIdByCopyId.size()) {
                return Optional.empty();
            }

            state.aggregatedResult = aggregate(state, marker.originTaskRunId());
            state.completed = true;
            state.emissionClaimed = true;
            return Optional.of(state.aggregatedResult);
        }
    }

    /**
     * Acknowledges that a coordinated result was emitted successfully. Completed aggregation
     * state remains cached to reject duplicate deliveries of both the original job and its
     * copies.
     *
     * @param result the result successfully emitted to the worker task result queue
     */
    public void acknowledgeResultEmission(WorkerTaskResult result) {
        String originTaskRunId = result.getTaskRun().getId();
        BroadcastState state = statesByOriginId.getIfPresent(originTaskRunId);
        if (state == null) {
            return;
        }

        synchronized (state) {
            if (!state.completed || state.emissionAcknowledged) {
                return;
            }
            state.emissionAcknowledged = true;
            statesByOriginId.put(originTaskRunId, state);
            state.workerIdByCopyId.keySet().forEach(copyId ->
            {
                completedCopyIds.put(copyId, true);
                copyStatesByCopyId.put(copyId, new CopyState(originTaskRunId, true));
            });
        }
    }

    /**
     * Releases a completed aggregate after all emission attempts failed, allowing a duplicate
     * copy result to claim and retry the same aggregate later.
     *
     * @param result the coordinated result that could not be emitted
     */
    public void releaseResultEmission(WorkerTaskResult result) {
        BroadcastState state = statesByOriginId.getIfPresent(result.getTaskRun().getId());
        if (state == null) {
            return;
        }

        synchronized (state) {
            if (state.completed && !state.emissionAcknowledged) {
                state.emissionClaimed = false;
            }
        }
    }

    /**
     * Fails a broadcast copy that can no longer be delivered to its target worker (worker gone,
     * or the copy could not be re-emitted). May complete the aggregation.
     *
     * @param copy the undeliverable per-worker copy
     * @return the result to emit to the worker task result queue, if any
     */
    public Optional<WorkerTaskResult> onCopyUndeliverable(WorkerTask copy) {
        return onResult(new WorkerTaskResult(copy.getTaskRun().fail()));
    }

    /**
     * Maps a copy task run id back to its original task run id, for log and metric remapping.
     *
     * @return the original task run id, or {@code null} when the id is not a known copy
     */
    public String resolveOriginTaskRunId(String taskRunId) {
        if (taskRunId == null) {
            return null;
        }
        CopyState state = copyStatesByCopyId.getIfPresent(taskRunId);
        return state == null ? null : state.originTaskRunId();
    }

    /**
     * Rewrites a copy result to the identity of the original task run.
     */
    private static WorkerTaskResult mapToOrigin(WorkerTaskResult result, String originTaskRunId) {
        TaskRun mapped = result.getTaskRun().toBuilder()
            .id(originTaskRunId)
            .broadcast(null)
            .build();
        return result.withTaskRun(mapped);
    }

    /**
     * Maps a copy result whose aggregation state was lost to a failed original result. A RUNNING
     * transition can still be forwarded safely, but a terminal success cannot be proven without
     * every copy result.
     */
    private static WorkerTaskResult mapResultWithoutAggregationState(WorkerTaskResult result, String originTaskRunId) {
        WorkerTaskResult mapped = mapToOrigin(result, originTaskRunId);
        return mapped.getTaskRun().getState().isTerminated()
            ? mapped.withTaskRun(mapped.getTaskRun().fail())
            : mapped;
    }

    /**
     * Builds the aggregated terminal result for the original task run: the task run of the copy
     * with the most severe terminal state (mapped back to the original identity), every dynamic
     * task run in worker snapshot order, and the outputs of every copy merged into a map keyed by
     * worker id.
     */
    private static WorkerTaskResult aggregate(BroadcastState state, String originTaskRunId) {
        WorkerTaskResult representative = state.terminalResultsByCopyId.values().stream()
            .max(Comparator.comparingInt(r -> severity(r.getTaskRun().getState().getCurrent())))
            .orElseThrow(() -> new IllegalStateException("Broadcast aggregation without any terminal result"));

        Map<String, Object> outputsByWorkerId = new HashMap<>();
        List<TaskRun> dynamicTaskRuns = new ArrayList<>();
        state.workerIdByCopyId.forEach((copyId, workerId) ->
        {
            WorkerTaskResult result = state.terminalResultsByCopyId.get(copyId);
            if (result.getOutputs() != null) {
                outputsByWorkerId.put(workerId, result.getOutputs());
            }
            if (result.getDynamicTaskRuns() != null) {
                dynamicTaskRuns.addAll(result.getDynamicTaskRuns());
            }
        });

        WorkerTaskResult mapped = mapToOrigin(representative, originTaskRunId);
        WorkerTaskResult aggregated = new WorkerTaskResult(
            mapped.getTaskRun(),
            dynamicTaskRuns,
            outputsByWorkerId.isEmpty() ? null : outputsByWorkerId
        );
        log.info(
            "Broadcast task run '{}' completed on {} worker(s) with aggregated state {}",
            originTaskRunId, state.workerIdByCopyId.size(), aggregated.getTaskRun().getState().getCurrent()
        );
        return aggregated;
    }

    /**
     * Severity used to pick the aggregated state: the worst copy state wins.
     */
    private static int severity(State.Type state) {
        return switch (state) {
            case FAILED -> 5;
            case KILLED -> 4;
            case CANCELLED -> 3;
            case WARNING -> 2;
            case SUCCESS -> 1;
            default -> 0;
        };
    }

    /**
     * Aggregation state for one fanned-out broadcast task. Guarded by {@code synchronized (this)}.
     */
    private static final class BroadcastState {
        private final Map<String, String> workerIdByCopyId = new LinkedHashMap<>();
        private final Map<String, WorkerTaskResult> terminalResultsByCopyId = new HashMap<>();
        private boolean runningForwarded;
        private boolean completed;
        private boolean emissionClaimed;
        private boolean emissionAcknowledged;
        private WorkerTaskResult aggregatedResult;

        private long expiryNanos() {
            return emissionAcknowledged ? COMPLETED_STATE_TTL.toNanos() : Long.MAX_VALUE;
        }
    }

    private record CopyState(String originTaskRunId, boolean completed) {
        private long expiryNanos() {
            return completed ? COMPLETED_STATE_TTL.toNanos() : Long.MAX_VALUE;
        }
    }
}
