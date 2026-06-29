package io.kestra.controller.grpc.services;

import java.util.List;

import io.kestra.core.worker.QueueSubscription;
import io.kestra.core.worker.WorkerGroups;
import io.kestra.core.worker.WorkerQueues;
import io.kestra.core.worker.WorkerRoutingConfiguration;

import jakarta.inject.Singleton;

/**
 * Static config-backed resolver for OSS worker group subscriptions.
 */
@Singleton
public class ConfiguredWorkerQueueResolver implements WorkerQueueResolver {
    private final WorkerRoutingConfiguration configuration;

    public ConfiguredWorkerQueueResolver(WorkerRoutingConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public List<QueueSubscription> resolve(String workerGroupId) {
        String groupId = WorkerGroups.normalize(workerGroupId);
        WorkerRoutingConfiguration.WorkerGroup group = configuration.groups().get(groupId);
        if (group != null && !group.queues().isEmpty()) {
            return group.queues();
        }

        if (configuration.queues().containsKey(groupId)) {
            return List.of(new QueueSubscription(groupId, QueueSubscription.NO_RESERVATION));
        }

        if (WorkerQueues.SYSTEM_ID.equals(groupId)) {
            return List.of(new QueueSubscription(WorkerQueues.SYSTEM_ID, QueueSubscription.NO_RESERVATION));
        }

        return List.of(QueueSubscription.DEFAULT);
    }
}
