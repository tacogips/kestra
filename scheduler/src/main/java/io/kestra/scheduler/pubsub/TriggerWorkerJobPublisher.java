package io.kestra.scheduler.pubsub;

import java.util.Optional;

import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import io.kestra.core.exceptions.InternalException;
import io.kestra.core.exceptions.NoMatchingWorkerQueueException;
import io.kestra.core.metrics.MetricRegistry;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.flows.FlowInterface;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.queues.KeyedDispatchQueueInterface;
import io.kestra.core.queues.QueueException;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.WorkerJobEvent;
import io.kestra.core.runners.WorkerQueueRouting;
import io.kestra.core.runners.WorkerTrigger;
import io.kestra.core.runners.WorkerTriggerData;
import io.kestra.core.scheduler.model.TriggerState;
import io.kestra.core.services.WorkerQueueService;
import io.kestra.core.utils.Logs;
import io.kestra.core.worker.WorkerQueues;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

//TODO should not be part of the scheduler
@Singleton
public class TriggerWorkerJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(TriggerWorkerJobPublisher.class);
    private static final String REASON_NO_MATCHING_WORKER_QUEUE = "no_matching_worker_queue";
    private static final String REASON_ROUTE_FAIL = "route_fail";
    private static final String REASON_ROUTE_CANCEL = "route_cancel";
    private static final String REASON_QUEUE_EMIT_ERROR = "queue_emit_error";

    private final WorkerQueueService workerQueueService;
    private final KeyedDispatchQueueInterface<WorkerJobEvent> workerJobEventQueue;
    private final MetricRegistry metricRegistry;

    @Inject
    public TriggerWorkerJobPublisher(
        WorkerQueueService workerQueueService,
        KeyedDispatchQueueInterface<WorkerJobEvent> workerJobEventQueue,
        MetricRegistry metricRegistry) {
        this.workerQueueService = workerQueueService;
        this.workerJobEventQueue = workerJobEventQueue;
        this.metricRegistry = metricRegistry;
    }

    /**
     * Sends the given trigger to a worker queue for evaluation.
     *
     * @return {@code true} if the trigger was dispatched to a worker queue; {@code false} if it was
     *         dropped (no matching worker queue, no available worker, or queue error).
     */
    public boolean send(TriggerState triggerState, AbstractTrigger trigger, FlowInterface flow, ConditionContext conditionContext) throws InternalException {

        if (log.isDebugEnabled()) {
            Logs.logTrigger(
                triggerState,
                Level.DEBUG,
                "[date: {}] Scheduling evaluation to the worker",
                triggerState.getEvaluatedAt()
            );
        }

        WorkerTrigger workerTrigger = WorkerTrigger
            .builder()
            .trigger(trigger)
            .data(WorkerTriggerData.from(conditionContext, triggerState.context()))
            .dispatchEpoch(triggerState.getDispatchEpoch())
            .build();
        Optional<WorkerQueueRouting> routing;
        try {
            routing = workerQueueService.resolveWorkerQueueForJob(flow, workerTrigger);
        } catch (NoMatchingWorkerQueueException e) {
            // No Worker Queue matches the requested tags — a configuration error.
            // Drop the trigger evaluation with a clear, user-facing log message.
            conditionContext.getRunContext().logger()
                .error("{}, ignoring the trigger.", e.getMessage());
            incrementRoutingFailureCounter(flow, trigger, REASON_NO_MATCHING_WORKER_QUEUE);
            return false;
        }
        try {
            if (routing.isEmpty() || routing.get().isDefault()) {
                // No routing or explicit default Worker Queue — dispatch with null key.
                this.workerJobEventQueue.emit(null, WorkerJobEvent.of(workerTrigger, null));
                return true;
            }
            WorkerQueueRouting r = routing.get();
            String workerQueueId = r.workerQueueId();
            String workerQueueForLog = WorkerQueues.forLog(r.tags(), workerQueueId);
            RunContext runContext = conditionContext.getRunContext();
            switch (r.disposition()) {
                case DISPATCH -> {
                    this.workerJobEventQueue.emit(workerQueueId, WorkerJobEvent.of(workerTrigger, workerQueueId));
                    return true;
                }
                case WAIT_AND_DISPATCH -> {
                    runContext.logger()
                        .info("No workers are available for {}, waiting for one to be available.", workerQueueForLog);
                    this.workerJobEventQueue.emit(workerQueueId, WorkerJobEvent.of(workerTrigger, workerQueueId));
                    return true;
                }
                case FAIL -> {
                    runContext.logger()
                        .error("No workers are available for {}, ignoring the trigger.", workerQueueForLog);
                    incrementRoutingFailureCounter(flow, trigger, REASON_ROUTE_FAIL);
                    return false;
                }
                case CANCEL -> {
                    runContext.logger()
                        .warn("No workers are available for {}, ignoring the trigger.", workerQueueForLog);
                    incrementRoutingFailureCounter(flow, trigger, REASON_ROUTE_CANCEL);
                    return false;
                }
            }
        } catch (QueueException e) {
            log.error("Unable to emit the Worker Trigger job", e);
            incrementRoutingFailureCounter(flow, trigger, REASON_QUEUE_EMIT_ERROR);
        }
        return false;
    }

    private void incrementRoutingFailureCounter(FlowInterface flow, AbstractTrigger trigger, String reason) {
        String[] tags = new String[] {
            MetricRegistry.TAG_NAMESPACE_ID, flow.getNamespace(),
            MetricRegistry.TAG_FLOW_ID, flow.getId(),
            MetricRegistry.TAG_TRIGGER_ID, trigger.getId(),
            MetricRegistry.TAG_TRIGGER_TYPE, trigger.getType(),
            MetricRegistry.TAG_REASON, reason
        };
        if (flow.getTenantId() != null) {
            tags = ArrayUtils.addAll(tags, MetricRegistry.TAG_TENANT_ID, flow.getTenantId());
        }
        metricRegistry
            .counter(
                MetricRegistry.METRIC_SCHEDULER_TRIGGER_WORKER_ROUTING_FAILURE_COUNT,
                MetricRegistry.METRIC_SCHEDULER_TRIGGER_WORKER_ROUTING_FAILURE_COUNT_DESCRIPTION,
                tags
            )
            .increment();
    }
}
