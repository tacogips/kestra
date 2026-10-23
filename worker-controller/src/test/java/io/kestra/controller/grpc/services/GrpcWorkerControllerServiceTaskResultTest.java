package io.kestra.controller.grpc.services;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import io.kestra.controller.grpc.OpaqueData;
import io.kestra.controller.grpc.RequestOrResponseHeader;
import io.kestra.controller.messages.BatchMessage;
import io.kestra.controller.messages.MessageFormats;
import io.kestra.core.executor.WorkerJobRunningStateStore;
import io.kestra.core.models.executions.TaskRun;
import io.kestra.core.models.flows.State;
import io.kestra.core.queues.DispatchQueueInterface;
import io.kestra.core.queues.QueueException;
import io.kestra.core.runners.RunContextLogger;
import io.kestra.core.runners.RunContextLoggerFactory;
import io.kestra.core.runners.WorkerTaskResult;
import io.kestra.core.utils.IdUtils;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for worker task result coordination in {@link GrpcWorkerControllerService}.
 */
@ExtendWith(MockitoExtension.class)
class GrpcWorkerControllerServiceTaskResultTest {

    @Mock
    private DispatchQueueInterface<WorkerTaskResult> workerTaskResultQueue;

    @Mock
    private WorkerJobRunningStateStore workerJobRunningStateStore;

    @Mock
    private BroadcastTaskCoordinator broadcastTaskCoordinator;

    @Mock
    private RunContextLoggerFactory runContextLoggerFactory;

    @Mock
    private RunContextLogger runContextLogger;

    @Mock
    private Logger logger;

    @InjectMocks
    private GrpcWorkerControllerService service;

    @Test
    void shouldAcknowledgeCoordinatedResultAfterSuccessfulEmission() throws Exception {
        // Given
        WorkerTaskResult incoming = terminalResult("copy-task-run");
        WorkerTaskResult coordinated = terminalResult("origin-task-run");
        when(broadcastTaskCoordinator.onResult(incoming)).thenReturn(Optional.of(coordinated));

        // When
        boolean handled = service.handleWorkerTaskResult(incoming);

        // Then
        assertThat(handled).isTrue();
        verify(workerTaskResultQueue).emit(coordinated);
        verify(broadcastTaskCoordinator).acknowledgeResultEmission(coordinated);
        verify(workerJobRunningStateStore).deleteByKey("copy-task-run");
    }

    @Test
    void shouldNotFailWhenSuppressedResultCleanupFails() {
        // Given
        WorkerTaskResult incoming = terminalResult("copy-task-run");
        when(broadcastTaskCoordinator.onResult(incoming)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("state store unavailable"))
            .when(workerJobRunningStateStore).deleteByKey("copy-task-run");

        // When / Then
        assertThatCode(() -> service.handleWorkerTaskResult(incoming)).doesNotThrowAnyException();
        verifyNoInteractions(workerTaskResultQueue);
    }

    @Test
    void shouldReturnRetryableGrpcErrorAndSucceedOnRedeliveryAfterQueueFailures() throws Exception {
        // Given
        WorkerTaskResult incoming = terminalResult("copy-task-run");
        WorkerTaskResult coordinated = terminalResult("origin-task-run");
        QueueException queueException = new QueueException("queue unavailable");
        when(broadcastTaskCoordinator.onResult(incoming)).thenReturn(Optional.of(coordinated));
        when(runContextLoggerFactory.create(coordinated)).thenReturn(runContextLogger);
        when(runContextLogger.logger()).thenReturn(logger);
        doThrow(queueException)
            .doThrow(queueException)
            .doNothing()
            .when(workerTaskResultQueue).emit(any(WorkerTaskResult.class));
        OpaqueData request = request(incoming);
        @SuppressWarnings("unchecked")
        StreamObserver<OpaqueData> firstResponse = mock(StreamObserver.class);
        @SuppressWarnings("unchecked")
        StreamObserver<OpaqueData> retryResponse = mock(StreamObserver.class);

        // When
        service.sendWorkerTaskResults(request, firstResponse);
        service.sendWorkerTaskResults(request, retryResponse);

        // Then
        org.mockito.ArgumentCaptor<Throwable> error = org.mockito.ArgumentCaptor.forClass(Throwable.class);
        verify(firstResponse).onError(error.capture());
        assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        verify(broadcastTaskCoordinator).releaseResultEmission(coordinated);
        verify(workerTaskResultQueue, times(3)).emit(any(WorkerTaskResult.class));
        verify(broadcastTaskCoordinator).acknowledgeResultEmission(coordinated);
        verify(workerJobRunningStateStore).deleteByKey("copy-task-run");
        verify(retryResponse).onNext(any(OpaqueData.class));
        verify(retryResponse).onCompleted();
    }

    private static OpaqueData request(WorkerTaskResult result) {
        return OpaqueData.newBuilder()
            .setHeader(RequestOrResponseHeader.newBuilder().setMessageFormat(MessageFormats.JSON.name()).build())
            .setMessage(MessageFormats.JSON.toByteString(BatchMessage.of(java.util.List.of(result))))
            .build();
    }

    private static WorkerTaskResult terminalResult(String taskRunId) {
        TaskRun taskRun = TaskRun.builder()
            .tenantId("tenant")
            .id(taskRunId)
            .executionId(IdUtils.create())
            .namespace("io.kestra.tests")
            .flowId("broadcast-flow")
            .taskId("task")
            .state(new State())
            .build()
            .withState(State.Type.SUCCESS);
        return new WorkerTaskResult(taskRun);
    }
}
