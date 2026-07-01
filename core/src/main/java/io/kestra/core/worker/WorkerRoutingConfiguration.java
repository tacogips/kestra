package io.kestra.core.worker;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.Valid;

/**
 * Static OSS worker routing configuration.
 * <p>
 * This intentionally stays separate from repository-backed Worker Group management so
 * a fork can keep rebasing upstream while still enabling deterministic worker routing.
 */
@Introspected
@ConfigurationProperties("kestra.worker.routing")
public record WorkerRoutingConfiguration(
    @Nullable String workerGroupId,
    @Valid @Nullable Map<String, GroupQueueMapping> groupQueueMappings,
    @Valid @Nullable Map<String, WorkerQueue> queues) {

    public WorkerRoutingConfiguration {
        groupQueueMappings = groupQueueMappings == null ? Map.of() : Map.copyOf(groupQueueMappings);
        queues = queues == null ? Map.of() : Map.copyOf(queues);
        validateGroupQueueMappings(groupQueueMappings, queues);
    }

    /**
     * @return {@code true} when static queue definitions are configured.
     */
    public boolean isRoutingConfigured() {
        return !queues.isEmpty();
    }

    private static void validateGroupQueueMappings(Map<String, GroupQueueMapping> groupQueueMappings, Map<String, WorkerQueue> queues) {
        Set<String> configuredQueueIds = queues.keySet().stream()
            .map(WorkerQueues::normalize)
            .collect(Collectors.toUnmodifiableSet());

        List<String> unknownQueueReferences = groupQueueMappings.entrySet().stream()
            .flatMap(
                entry -> entry.getValue().queues().stream()
                    .map(QueueSubscription::workerQueueId)
                    .map(WorkerQueues::normalize)
                    .filter(queueId -> !isReservedQueue(queueId))
                    .filter(queueId -> !configuredQueueIds.contains(queueId))
                    .map(queueId -> entry.getKey() + " -> " + queueId)
            )
            .toList();

        if (!unknownQueueReferences.isEmpty()) {
            throw new IllegalArgumentException(
                "kestra.worker.routing.groupQueueMappings references undefined worker queues: " + unknownQueueReferences
            );
        }
    }

    private static boolean isReservedQueue(String queueId) {
        return WorkerQueues.DEFAULT_ID.equals(queueId) || WorkerQueues.SYSTEM_ID.equals(queueId);
    }

    /**
     * Static mapping from a Worker Group id to the Worker Queues subscribed by
     * workers in that group.
     */
    @Introspected
    public record GroupQueueMapping(@Valid @Nullable List<QueueSubscription> queues) {
        public GroupQueueMapping {
            queues = queues == null ? List.of() : List.copyOf(queues);
        }
    }

    /**
     * Static Worker Queue definition used by {@code workerSelector.tags}.
     */
    @Introspected
    public record WorkerQueue(@Nullable List<String> tags, @Nullable List<String> tenants) {
        public WorkerQueue {
            tags = tags == null ? List.of() : List.copyOf(tags);
            tenants = tenants == null ? List.of() : List.copyOf(tenants);
        }
    }
}
