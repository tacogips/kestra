package io.kestra.controller.grpc.services;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.models.executions.TaskRun;
import io.kestra.core.models.executions.TaskRunBroadcast;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.tasks.WorkerSelector;
import io.kestra.core.runners.WorkerTask;
import io.kestra.core.runners.WorkerTaskData;
import io.kestra.core.runners.WorkerTaskResult;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.core.debug.Return;
import io.kestra.plugin.core.flow.WorkingDirectory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BroadcastTaskCoordinator}.
 */
class BroadcastTaskCoordinatorTest {

    private BroadcastTaskCoordinator coordinator;
    private AtomicLong tickerNanoseconds;

    @BeforeEach
    void setUp() {
        tickerNanoseconds = new AtomicLong();
        coordinator = new BroadcastTaskCoordinator(tickerNanoseconds::get);
    }

    @Test
    void shouldBroadcastByDefaultOnConfiguredWorkerQueue() {
        // Given
        WorkerTask noSelector = workerTask(null);
        WorkerTask tagsOnly = workerTask(new WorkerSelector(List.of("batch"), null));
        WorkerTask explicitTrue = workerTask(new WorkerSelector(List.of("batch"), null, null, true));

        // When / Then
        assertThat(coordinator.isBroadcastTask(noSelector, "batchgroup1")).isFalse();
        assertThat(coordinator.isBroadcastTask(tagsOnly, "batchgroup1")).isTrue();
        assertThat(coordinator.isBroadcastTask(explicitTrue, "batchgroup1")).isTrue();
    }

    @Test
    void shouldNotBroadcastWhenSelectorExplicitlyDisablesIt() {
        // Given
        WorkerTask singleDispatch = workerTask(new WorkerSelector(List.of("batch"), null, null, false));

        // When / Then
        assertThat(coordinator.isBroadcastTask(singleDispatch, "batchgroup1")).isFalse();
    }

    @Test
    void shouldNotBroadcastOnDefaultOrSystemWorkerQueue() {
        // Given
        WorkerTask workerTask = workerTask(null);

        // When / Then - the default (empty dispatch key) and system queues stay single-dispatch
        assertThat(coordinator.isBroadcastTask(workerTask, "")).isFalse();
        assertThat(coordinator.isBroadcastTask(workerTask, null)).isFalse();
        assertThat(coordinator.isBroadcastTask(workerTask, "default")).isFalse();
        assertThat(coordinator.isBroadcastTask(workerTask, "system")).isFalse();
    }

    @Test
    void shouldNotDetectCopyAsBroadcastTask() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        WorkerTask copy = coordinator.fanOut(broadcastTask, List.of("worker-1")).getFirst();

        // When / Then
        assertThat(coordinator.isBroadcastTask(copy, "batchgroup1")).isFalse();
    }

    @Test
    void shouldNotDetectWorkingDirectoryAsBroadcastTask() {
        // Given
        WorkingDirectory workingDirectory = WorkingDirectory.builder()
            .id("wdir")
            .type(WorkingDirectory.class.getName())
            .workerSelector(new WorkerSelector(List.of("batch"), null))
            .build();
        WorkerTask workerTask = WorkerTask.builder()
            .taskRun(taskRun("wdir"))
            .task(workingDirectory)
            .data(workerTaskData())
            .build();

        // When / Then
        assertThat(coordinator.isBroadcastTask(workerTask, "batchgroup1")).isFalse();
    }

    @Test
    void shouldFanOutOnePinnedCopyPerWorker() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        String originId = broadcastTask.getTaskRun().getId();

        // When
        List<WorkerTask> copies = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2", "worker-3"));

        // Then
        assertThat(copies).hasSize(3);
        assertThat(copies).extracting(copy -> copy.getTaskRun().getBroadcast().targetWorkerId())
            .containsExactly("worker-1", "worker-2", "worker-3");
        assertThat(copies).allSatisfy(copy ->
        {
            assertThat(copy.getTaskRun().getBroadcast().originTaskRunId()).isEqualTo(originId);
            assertThat(copy.getTaskRun().getId()).isNotEqualTo(originId);
            assertThat(copy.getTask()).isSameAs(broadcastTask.getTask());
        });
        assertThat(copies).extracting(copy -> copy.getTaskRun().getId()).doesNotHaveDuplicates();
    }

    @Test
    void shouldIgnoreDuplicateFanOut() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        coordinator.fanOut(broadcastTask, List.of("worker-1"));

        // When
        List<WorkerTask> duplicate = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2"));

        // Then
        assertThat(duplicate).isEmpty();
    }

    @Test
    void shouldPassThroughNonBroadcastResult() {
        // Given
        WorkerTaskResult result = new WorkerTaskResult(taskRun("task").withState(State.Type.SUCCESS));

        // When
        Optional<WorkerTaskResult> emitted = coordinator.onResult(result);

        // Then
        assertThat(emitted).containsSame(result);
    }

    @Test
    void shouldForwardOnlyFirstRunningResultMappedToOrigin() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        String originId = broadcastTask.getTaskRun().getId();
        List<WorkerTask> copies = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2"));

        // When
        Optional<WorkerTaskResult> first = coordinator.onResult(
            new WorkerTaskResult(copies.get(0).getTaskRun().withState(State.Type.RUNNING))
        );
        Optional<WorkerTaskResult> second = coordinator.onResult(
            new WorkerTaskResult(copies.get(1).getTaskRun().withState(State.Type.RUNNING))
        );

        // Then
        assertThat(first).isPresent();
        assertThat(first.get().getTaskRun().getId()).isEqualTo(originId);
        assertThat(first.get().getTaskRun().getBroadcast()).isNull();
        assertThat(first.get().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.RUNNING);
        assertThat(second).isEmpty();
    }

    @Test
    void shouldAggregateWhenAllCopiesTerminal() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        String originId = broadcastTask.getTaskRun().getId();
        List<WorkerTask> copies = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2"));

        // When
        Optional<WorkerTaskResult> partial = coordinator.onResult(
            new WorkerTaskResult(copies.get(0).getTaskRun().withState(State.Type.SUCCESS))
        );
        Optional<WorkerTaskResult> complete = coordinator.onResult(
            new WorkerTaskResult(copies.get(1).getTaskRun().withState(State.Type.SUCCESS))
        );

        // Then
        assertThat(partial).isEmpty();
        assertThat(complete).isPresent();
        assertThat(complete.get().getTaskRun().getId()).isEqualTo(originId);
        assertThat(complete.get().getTaskRun().getBroadcast()).isNull();
        assertThat(complete.get().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    void shouldRetainActiveAggregationForLongRunningTask() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        String originId = broadcastTask.getTaskRun().getId();
        List<WorkerTask> copies = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2"));

        // When - no result is received for longer than the completed-state retention period.
        tickerNanoseconds.addAndGet(Duration.ofHours(25).toNanos());
        Optional<WorkerTaskResult> partial = coordinator.onResult(
            new WorkerTaskResult(copies.get(0).getTaskRun().withState(State.Type.SUCCESS))
        );
        Optional<WorkerTaskResult> complete = coordinator.onResult(
            new WorkerTaskResult(copies.get(1).getTaskRun().withState(State.Type.SUCCESS))
        );

        // Then
        assertThat(partial).isEmpty();
        assertThat(complete).isPresent();
        assertThat(complete.get().getTaskRun().getId()).isEqualTo(originId);
        assertThat(complete.get().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(coordinator.resolveOriginTaskRunId(copies.getFirst().getTaskRun().getId())).isEqualTo(originId);
    }

    @Test
    void shouldAggregateWorstStateWhenAnyCopyFails() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        List<WorkerTask> copies = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2"));

        // When
        coordinator.onResult(new WorkerTaskResult(copies.get(0).getTaskRun().withState(State.Type.SUCCESS)));
        Optional<WorkerTaskResult> complete = coordinator.onResult(
            new WorkerTaskResult(copies.get(1).getTaskRun().withState(State.Type.FAILED))
        );

        // Then
        assertThat(complete).isPresent();
        assertThat(complete.get().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    @Test
    void shouldMergeOutputsPerWorker() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        List<WorkerTask> copies = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2"));

        // When
        coordinator.onResult(
            new WorkerTaskResult(
                copies.get(0).getTaskRun().withState(State.Type.SUCCESS), Map.of("value", "from-1")
            )
        );
        Optional<WorkerTaskResult> complete = coordinator.onResult(
            new WorkerTaskResult(
                copies.get(1).getTaskRun().withState(State.Type.SUCCESS), Map.of("value", "from-2")
            )
        );

        // Then
        assertThat(complete).isPresent();
        assertThat(complete.get().getOutputs()).isEqualTo(
            Map.of(
                "worker-1", Map.of("value", "from-1"),
                "worker-2", Map.of("value", "from-2")
            )
        );
    }

    @Test
    void shouldMergeDynamicTaskRunsInWorkerSnapshotOrder() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        List<WorkerTask> copies = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2"));
        TaskRun firstDynamicTaskRun = taskRun("dynamic-1");
        TaskRun secondDynamicTaskRun = taskRun("dynamic-2");

        // When - results arrive in the opposite order from the worker snapshot.
        coordinator.onResult(
            new WorkerTaskResult(
                copies.get(1).getTaskRun().withState(State.Type.SUCCESS),
                List.of(secondDynamicTaskRun),
                null
            )
        );
        Optional<WorkerTaskResult> complete = coordinator.onResult(
            new WorkerTaskResult(
                copies.get(0).getTaskRun().withState(State.Type.SUCCESS),
                List.of(firstDynamicTaskRun),
                null
            )
        );

        // Then
        assertThat(complete).isPresent();
        assertThat(complete.get().getDynamicTaskRuns())
            .extracting(TaskRun::getId)
            .containsExactly(firstDynamicTaskRun.getId(), secondDynamicTaskRun.getId());
    }

    @Test
    void shouldMapResultToOriginWhenNoAggregationStateExists() {
        // Given - a copy result whose aggregation state is unknown (e.g. controller restart)
        String originId = IdUtils.create();
        TaskRun orphanCopy = taskRun("task").toBuilder()
            .broadcast(new TaskRunBroadcast(originId, "worker-1"))
            .build()
            .withState(State.Type.SUCCESS);

        // When
        Optional<WorkerTaskResult> emitted = coordinator.onResult(new WorkerTaskResult(orphanCopy));

        // Then
        assertThat(emitted).isPresent();
        assertThat(emitted.get().getTaskRun().getId()).isEqualTo(originId);
        assertThat(emitted.get().getTaskRun().getBroadcast()).isNull();
        assertThat(emitted.get().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    @Test
    void shouldRetryAggregateAfterEmissionFailureAndIgnoreAfterAcknowledgement() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        List<WorkerTask> copies = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2"));
        coordinator.onResult(new WorkerTaskResult(copies.get(0).getTaskRun().withState(State.Type.SUCCESS)));
        WorkerTaskResult finalCopyResult = new WorkerTaskResult(copies.get(1).getTaskRun().withState(State.Type.SUCCESS));

        // When - the first caller claims the aggregate, then its queue emission fails.
        WorkerTaskResult firstAggregate = coordinator.onResult(finalCopyResult).orElseThrow();
        Optional<WorkerTaskResult> whileClaimed = coordinator.onResult(finalCopyResult);
        coordinator.releaseResultEmission(firstAggregate);
        WorkerTaskResult retriedAggregate = coordinator.onResult(finalCopyResult).orElseThrow();
        coordinator.acknowledgeResultEmission(retriedAggregate);
        Optional<WorkerTaskResult> afterAcknowledgement = coordinator.onResult(finalCopyResult);

        // Then
        assertThat(whileClaimed).isEmpty();
        assertThat(retriedAggregate).isEqualTo(firstAggregate);
        assertThat(afterAcknowledgement).isEmpty();
    }

    @Test
    void shouldIgnoreCopyOutsideActiveAggregation() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        String originId = broadcastTask.getTaskRun().getId();
        coordinator.fanOut(broadcastTask, List.of("worker-1"));
        TaskRun staleCopy = taskRun("task").toBuilder()
            .broadcast(new TaskRunBroadcast(originId, "stale-worker"))
            .build()
            .withState(State.Type.SUCCESS);

        // When
        Optional<WorkerTaskResult> emitted = coordinator.onResult(new WorkerTaskResult(staleCopy));

        // Then
        assertThat(emitted).isEmpty();
    }

    @Test
    void shouldRejectDuplicateFanOutAfterAggregateIsAcknowledged() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        WorkerTask copy = coordinator.fanOut(broadcastTask, List.of("worker-1")).getFirst();
        WorkerTaskResult aggregate = coordinator.onResult(
            new WorkerTaskResult(copy.getTaskRun().withState(State.Type.SUCCESS))
        ).orElseThrow();
        coordinator.acknowledgeResultEmission(aggregate);

        // When
        List<WorkerTask> duplicate = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2"));

        // Then
        assertThat(duplicate).isEmpty();
    }

    @Test
    void shouldExpireAcknowledgedStateAfterRetentionPeriod() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        WorkerTask copy = coordinator.fanOut(broadcastTask, List.of("worker-1")).getFirst();
        WorkerTaskResult aggregate = coordinator.onResult(
            new WorkerTaskResult(copy.getTaskRun().withState(State.Type.SUCCESS))
        ).orElseThrow();
        coordinator.acknowledgeResultEmission(aggregate);

        // When
        tickerNanoseconds.addAndGet(Duration.ofHours(25).toNanos());
        List<WorkerTask> redelivered = coordinator.fanOut(broadcastTask, List.of("worker-1"));

        // Then
        assertThat(coordinator.resolveOriginTaskRunId(copy.getTaskRun().getId())).isNull();
        assertThat(redelivered).hasSize(1);
    }

    @Test
    void shouldFailUndeliverableCopyAndCompleteAggregation() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        String originId = broadcastTask.getTaskRun().getId();
        List<WorkerTask> copies = coordinator.fanOut(broadcastTask, List.of("worker-1", "worker-2"));
        coordinator.onResult(new WorkerTaskResult(copies.get(0).getTaskRun().withState(State.Type.SUCCESS)));

        // When - the second copy's worker is gone
        Optional<WorkerTaskResult> complete = coordinator.onCopyUndeliverable(copies.get(1));

        // Then - the aggregation completes with the failed copy dominating
        assertThat(complete).isPresent();
        assertThat(complete.get().getTaskRun().getId()).isEqualTo(originId);
        assertThat(complete.get().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    @Test
    void shouldResolveOriginTaskRunIdForCopies() {
        // Given
        WorkerTask broadcastTask = broadcastWorkerTask();
        String originId = broadcastTask.getTaskRun().getId();
        List<WorkerTask> copies = coordinator.fanOut(broadcastTask, List.of("worker-1"));

        // When / Then
        assertThat(coordinator.resolveOriginTaskRunId(copies.getFirst().getTaskRun().getId())).isEqualTo(originId);
        assertThat(coordinator.resolveOriginTaskRunId("unknown")).isNull();
        assertThat(coordinator.resolveOriginTaskRunId(null)).isNull();
    }

    private static WorkerTask broadcastWorkerTask() {
        return workerTask(new WorkerSelector(List.of("batch"), null));
    }

    private static WorkerTask workerTask(WorkerSelector workerSelector) {
        Return task = Return.builder()
            .id("task")
            .type(Return.class.getName())
            .workerSelector(workerSelector)
            .build();
        return WorkerTask.builder()
            .taskRun(taskRun(task.getId()))
            .task(task)
            .data(workerTaskData())
            .build();
    }

    private static TaskRun taskRun(String taskId) {
        return TaskRun.builder()
            .tenantId("tenant")
            .id(IdUtils.create())
            .executionId(IdUtils.create())
            .namespace("io.kestra.tests")
            .flowId("broadcast-flow")
            .taskId(taskId)
            .state(new State())
            .build();
    }

    private static WorkerTaskData workerTaskData() {
        return new WorkerTaskData(Map.of(), List.of(), null);
    }

}
