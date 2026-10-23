package io.kestra.controller.grpc.services;

import java.util.List;

import io.kestra.core.worker.QueueSubscription;
import io.kestra.core.worker.WorkerGroups;
import io.kestra.core.worker.WorkerQueues;
import io.kestra.core.worker.WorkerRoutingConfiguration;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Static config-backed resolver for OSS worker group subscriptions.
 */
@Slf4j
@Singleton
public class ConfiguredWorkerQueueResolver implements WorkerQueueResolver {
    private final WorkerRoutingConfiguration configuration;

    public ConfiguredWorkerQueueResolver(WorkerRoutingConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public List<QueueSubscription> resolve(String workerGroupId) {
        String groupId = WorkerGroups.normalize(workerGroupId);
        WorkerRoutingConfiguration.GroupQueueMapping group = configuration.groupQueueMappings().get(groupId);
        if (group != null && !group.queues().isEmpty()) {
            return group.queues();
        }

        if (configuration.queues().containsKey(groupId)) {
            return List.of(new QueueSubscription(groupId, QueueSubscription.NO_RESERVATION));
        }

        if (WorkerQueues.SYSTEM_ID.equals(groupId)) {
            return List.of(new QueueSubscription(WorkerQueues.SYSTEM_ID, QueueSubscription.NO_RESERVATION));
        }

        if (!WorkerGroups.DEFAULT_ID.equals(groupId)) {
            log.warn(
                "Unknown worker group '{}' resolved to the default worker queue subscription; check kestra.worker.routing.workerGroupId and groupQueueMappings",
                groupId
            );
        }

        return List.of(QueueSubscription.DEFAULT);
    }
}
