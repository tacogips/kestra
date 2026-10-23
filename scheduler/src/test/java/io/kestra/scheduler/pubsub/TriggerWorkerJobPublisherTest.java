package io.kestra.scheduler.pubsub;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import io.kestra.core.exceptions.NoMatchingWorkerQueueException;
import io.kestra.core.metrics.MetricConfig;
import io.kestra.core.metrics.MetricRegistry;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.FlowInterface;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.queues.KeyedDispatchQueueInterface;
import io.kestra.core.queues.QueueException;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.WorkerJob;
import io.kestra.core.runners.WorkerJobEvent;
import io.kestra.core.runners.WorkerQueueRouting;
import io.kestra.core.scheduler.model.TriggerState;
import io.kestra.core.services.WorkerQueueService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TriggerWorkerJobPublisherTest {
    private static final String TENANT_ID = "tenant";
    private static final String NAMESPACE = "io.kestra.unittest";
    private static final String FLOW_ID = "flow";
    private static final String TRIGGER_ID = "trigger";
    private static final String TRIGGER_TYPE = "test.Trigger";
    private static final Instant EVALUATED_AT = Instant.parse("2026-07-02T00:00:00Z");

    private WorkerQueueService workerQueueService;
    private KeyedDispatchQueueInterface<WorkerJobEvent> workerJobEventQueue;
    private MetricRegistry metricRegistry;
    private TriggerWorkerJobPublisher publisher;
    private FlowInterface flow;
    private TestTrigger trigger;
    private ConditionContext conditionContext;
    private TriggerState triggerState;

    @BeforeEach
    void setUp() {
        workerQueueService = mock(WorkerQueueService.class);
        workerJobEventQueue = mock(KeyedDispatchQueueInterface.class);
        metricRegistry = new MetricRegistry(new SimpleMeterRegistry(), new MetricConfig());
        publisher = new TriggerWorkerJobPublisher(workerQueueService, workerJobEventQueue, metricRegistry);
        flow = Flow.builder()
            .tenantId(TENANT_ID)
            .namespace(NAMESPACE)
            .id(FLOW_ID)
            .revision(1)
            .build();
        trigger = TestTrigger.builder()
            .id(TRIGGER_ID)
            .type(TRIGGER_TYPE)
            .build();
        RunContext runContext = mock(RunContext.class);
        when(runContext.logger()).thenReturn(mock(Logger.class));
        conditionContext = ConditionContext.builder()
            .flow(flow)
            .runContext(runContext)
            .build();
        triggerState = TriggerState.builder()
            .tenantId(TENANT_ID)
            .namespace(NAMESPACE)
            .flowId(FLOW_ID)
            .triggerId(TRIGGER_ID)
            .evaluatedAt(EVALUATED_AT)
            .dispatchEpoch(1L)
            .build();
    }

    @Test
    void shouldIncrementRoutingFailureCounterWhenNoMatchingWorkerQueue() throws Exception {
        // Given
        when(workerQueueService.resolveWorkerQueueForJob(any(), any(WorkerJob.class)))
            .thenThrow(new NoMatchingWorkerQueueException(List.of("gpu"), TENANT_ID, TRIGGER_ID));

        // When
        boolean dispatched = publisher.send(triggerState, trigger, flow, conditionContext);

        // Then
        assertThat(dispatched).isFalse();
        verify(workerJobEventQueue, never()).emit(nullable(String.class), any(WorkerJobEvent.class));
        assertRoutingFailureCounter("no_matching_worker_queue", 1);
    }

    @Test
    void shouldIncrementRoutingFailureCounterWhenFailDispositionDropsTrigger() throws Exception {
        // Given
        when(workerQueueService.resolveWorkerQueueForJob(any(), any(WorkerJob.class)))
            .thenReturn(Optional.of(new WorkerQueueRouting("gpu", List.of("gpu"), null, WorkerQueueRouting.Disposition.FAIL)));

        // When
        boolean dispatched = publisher.send(triggerState, trigger, flow, conditionContext);

        // Then
        assertThat(dispatched).isFalse();
        verify(workerJobEventQueue, never()).emit(nullable(String.class), any(WorkerJobEvent.class));
        assertRoutingFailureCounter("route_fail", 1);
    }

    @Test
    void shouldIncrementRoutingFailureCounterWhenCancelDispositionDropsTrigger() throws Exception {
        // Given
        when(workerQueueService.resolveWorkerQueueForJob(any(), any(WorkerJob.class)))
            .thenReturn(Optional.of(new WorkerQueueRouting("gpu", List.of("gpu"), null, WorkerQueueRouting.Disposition.CANCEL)));

        // When
        boolean dispatched = publisher.send(triggerState, trigger, flow, conditionContext);

        // Then
        assertThat(dispatched).isFalse();
        verify(workerJobEventQueue, never()).emit(nullable(String.class), any(WorkerJobEvent.class));
        assertRoutingFailureCounter("route_cancel", 1);
    }

    @Test
    void shouldIncrementRoutingFailureCounterWhenQueueEmitFails() throws Exception {
        // Given
        when(workerQueueService.resolveWorkerQueueForJob(any(), any(WorkerJob.class)))
            .thenReturn(Optional.of(new WorkerQueueRouting("gpu", List.of("gpu"), null, WorkerQueueRouting.Disposition.DISPATCH)));
        doThrow(new QueueException("boom"))
            .when(workerJobEventQueue)
            .emit(nullable(String.class), any(WorkerJobEvent.class));

        // When
        boolean dispatched = publisher.send(triggerState, trigger, flow, conditionContext);

        // Then
        assertThat(dispatched).isFalse();
        assertRoutingFailureCounter("queue_emit_error", 1);
    }

    @Test
    void shouldKeepDefaultRoutingDispatchBehaviorUnchanged() throws Exception {
        // Given
        when(workerQueueService.resolveWorkerQueueForJob(any(), any(WorkerJob.class)))
            .thenReturn(Optional.empty());

        // When
        boolean dispatched = publisher.send(triggerState, trigger, flow, conditionContext);

        // Then
        assertThat(dispatched).isTrue();
        ArgumentCaptor<WorkerJobEvent> eventCaptor = ArgumentCaptor.forClass(WorkerJobEvent.class);
        verify(workerJobEventQueue).emit(nullable(String.class), eventCaptor.capture());
        assertThat(eventCaptor.getValue().workerQueueId()).isNull();
        assertRoutingFailureCounterAbsent("queue_emit_error");
    }

    @Test
    void shouldKeepDispatchDispositionBehaviorUnchanged() throws Exception {
        // Given
        when(workerQueueService.resolveWorkerQueueForJob(any(), any(WorkerJob.class)))
            .thenReturn(Optional.of(new WorkerQueueRouting("gpu", List.of("gpu"), null, WorkerQueueRouting.Disposition.DISPATCH)));

        // When
        boolean dispatched = publisher.send(triggerState, trigger, flow, conditionContext);

        // Then
        assertThat(dispatched).isTrue();
        ArgumentCaptor<WorkerJobEvent> eventCaptor = ArgumentCaptor.forClass(WorkerJobEvent.class);
        verify(workerJobEventQueue).emit(nullable(String.class), eventCaptor.capture());
        assertThat(eventCaptor.getValue().workerQueueId()).isEqualTo("gpu");
        assertRoutingFailureCounterAbsent("route_fail");
    }

    @Test
    void shouldKeepWaitAndDispatchDispositionBehaviorUnchanged() throws Exception {
        // Given
        when(workerQueueService.resolveWorkerQueueForJob(any(), any(WorkerJob.class)))
            .thenReturn(Optional.of(new WorkerQueueRouting("gpu", List.of("gpu"), null, WorkerQueueRouting.Disposition.WAIT_AND_DISPATCH)));

        // When
        boolean dispatched = publisher.send(triggerState, trigger, flow, conditionContext);

        // Then
        assertThat(dispatched).isTrue();
        ArgumentCaptor<WorkerJobEvent> eventCaptor = ArgumentCaptor.forClass(WorkerJobEvent.class);
        verify(workerJobEventQueue).emit(nullable(String.class), eventCaptor.capture());
        assertThat(eventCaptor.getValue().workerQueueId()).isEqualTo("gpu");
        assertRoutingFailureCounterAbsent("route_fail");
    }

    private void assertRoutingFailureCounter(String reason, double expectedCount) {
        Counter counter = metricRegistry.find(MetricRegistry.METRIC_SCHEDULER_TRIGGER_WORKER_ROUTING_FAILURE_COUNT)
            .tags(
                MetricRegistry.TAG_NAMESPACE_ID, NAMESPACE,
                MetricRegistry.TAG_FLOW_ID, FLOW_ID,
                MetricRegistry.TAG_TRIGGER_ID, TRIGGER_ID,
                MetricRegistry.TAG_TRIGGER_TYPE, TRIGGER_TYPE,
                MetricRegistry.TAG_REASON, reason,
                MetricRegistry.TAG_TENANT_ID, TENANT_ID
            )
            .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(expectedCount);
    }

    private void assertRoutingFailureCounterAbsent(String reason) {
        Counter counter = metricRegistry.find(MetricRegistry.METRIC_SCHEDULER_TRIGGER_WORKER_ROUTING_FAILURE_COUNT)
            .tags(
                MetricRegistry.TAG_NAMESPACE_ID, NAMESPACE,
                MetricRegistry.TAG_FLOW_ID, FLOW_ID,
                MetricRegistry.TAG_TRIGGER_ID, TRIGGER_ID,
                MetricRegistry.TAG_TRIGGER_TYPE, TRIGGER_TYPE,
                MetricRegistry.TAG_REASON, reason,
                MetricRegistry.TAG_TENANT_ID, TENANT_ID
            )
            .counter();

        assertThat(counter).isNull();
    }

    @Plugin(internal = true)
    @SuperBuilder
    @NoArgsConstructor
    @Getter
    public static class TestTrigger extends AbstractTrigger {
    }
}
