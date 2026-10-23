package io.kestra.core.services;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.kestra.core.exceptions.NoMatchingWorkerQueueException;
import io.kestra.core.models.flows.FlowInterface;
import io.kestra.core.models.tasks.WorkerQueueFallback;
import io.kestra.core.models.tasks.WorkerSelector;
import io.kestra.core.runners.WorkerJob;
import io.kestra.core.runners.WorkerQueueMetaStore;
import io.kestra.core.runners.WorkerQueueRouting;
import io.kestra.core.runners.WorkerTask;
import io.kestra.core.runners.WorkerTrigger;
import io.kestra.core.worker.WorkerRoutingConfiguration;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * OSS config-backed Worker Queue routing.
 * <p>
 * It reuses the existing {@code workerSelector} model and dispatcher queue key
 * instead of adding a second routing path.
 */
@Singleton
@Requires(bean = WorkerQueueMetaStore.class)
public class ConfiguredWorkerQueueService extends WorkerQueueService.Default {
    private final WorkerQueueMetaStore workerQueueMetaStore;
    private final WorkerRoutingConfiguration workerRoutingConfiguration;

    public ConfiguredWorkerQueueService(WorkerQueueMetaStore workerQueueMetaStore, WorkerRoutingConfiguration workerRoutingConfiguration) {
        this.workerQueueMetaStore = workerQueueMetaStore;
        this.workerRoutingConfiguration = workerRoutingConfiguration;
    }

    @Override
    protected Optional<WorkerQueueRouting> doResolveWorkerQueueForJob(FlowInterface flow, WorkerJob workerJob) throws NoMatchingWorkerQueueException {
        if (!workerRoutingConfiguration.isRoutingConfigured()) {
            return Optional.empty();
        }

        WorkerSelector selector = selectorFor(flow, workerJob);
        if (selector == null || selector.tags() == null || selector.tags().isEmpty()) {
            return Optional.empty();
        }

        Set<String> requiredTags = new LinkedHashSet<>(selector.tags());
        List<String> queueIds = workerQueueMetaStore.resolveQueueIdsByTags(requiredTags, flow.getTenantId(), selector.match());
        if (queueIds.isEmpty()) {
            if (WorkerQueueFallback.IGNORE.equals(selector.fallback())) {
                return Optional.of(WorkerQueueRouting.toDefault());
            }
            throw new NoMatchingWorkerQueueException(requiredTags, flow.getTenantId(), source(workerJob));
        }

        Optional<String> availableQueueId = queueIds.stream()
            .filter(workerQueueMetaStore::hasActiveWorkerForQueue)
            .findFirst();
        if (availableQueueId.isPresent()) {
            return Optional.of(new WorkerQueueRouting(availableQueueId.get(), selector.tags(), null, WorkerQueueRouting.Disposition.DISPATCH));
        }

        String queueId = queueIds.getFirst();
        WorkerQueueFallback fallback = selector.fallback() == null ? WorkerQueueFallback.FAIL : selector.fallback();
        return switch (fallback) {
            case WAIT -> Optional.of(new WorkerQueueRouting(queueId, selector.tags(), fallback, WorkerQueueRouting.Disposition.WAIT_AND_DISPATCH));
            case CANCEL -> Optional.of(new WorkerQueueRouting(queueId, selector.tags(), fallback, WorkerQueueRouting.Disposition.CANCEL));
            case IGNORE -> Optional.of(WorkerQueueRouting.toDefault());
            case FAIL -> Optional.of(new WorkerQueueRouting(queueId, selector.tags(), fallback, WorkerQueueRouting.Disposition.FAIL));
        };
    }

    private static WorkerSelector selectorFor(FlowInterface flow, WorkerJob workerJob) {
        if (workerJob instanceof WorkerTask workerTask && workerTask.getTask().getWorkerSelector() != null) {
            return workerTask.getTask().getWorkerSelector();
        }
        if (workerJob instanceof WorkerTrigger workerTrigger && workerTrigger.getTrigger().getWorkerSelector() != null) {
            return workerTrigger.getTrigger().getWorkerSelector();
        }
        return flow.getWorkerSelector();
    }

    private static String source(WorkerJob workerJob) {
        if (workerJob instanceof WorkerTask workerTask) {
            return "task:" + workerTask.getTask().getId();
        }
        if (workerJob instanceof WorkerTrigger workerTrigger) {
            return "trigger:" + workerTrigger.getTrigger().getId();
        }
        return workerJob.uid();
    }
}
