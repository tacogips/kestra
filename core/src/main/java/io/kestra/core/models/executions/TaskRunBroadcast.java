package io.kestra.core.models.executions;

import jakarta.validation.constraints.NotNull;

/**
 * Marker carried by a {@link TaskRun} that is a per-worker copy of a broadcast task.
 * <p>
 * Unless a task declares {@code workerSelector.broadcast: false}, the worker-controller fans the
 * original {@link io.kestra.core.runners.WorkerTask} out into one copy per worker currently
 * subscribed to the resolved Worker Queue. Each copy gets a fresh task run id plus this marker,
 * which survives the whole round trip (dispatch, worker execution, result, resubmission) so any
 * component can map the copy back to the original task run.
 *
 * @param originTaskRunId the id of the original task run created by the executor
 * @param targetWorkerId the id of the worker this copy is pinned to
 */
public record TaskRunBroadcast(
    @NotNull String originTaskRunId,
    @NotNull String targetWorkerId) {
}
