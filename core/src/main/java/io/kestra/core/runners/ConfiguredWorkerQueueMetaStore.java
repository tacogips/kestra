package io.kestra.core.runners;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import io.kestra.core.models.tasks.WorkerSelectorMatch;
import io.kestra.core.worker.QueueSubscription;
import io.kestra.core.worker.WorkerQueues;
import io.kestra.core.worker.WorkerRoutingConfiguration;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * Static config-backed Worker Queue metadata store for OSS deployments.
 */
@Singleton
@Requires(bean = WorkerRoutingConfiguration.class)
public class ConfiguredWorkerQueueMetaStore implements WorkerQueueMetaStore {
    private final WorkerRoutingConfiguration configuration;

    public ConfiguredWorkerQueueMetaStore(WorkerRoutingConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public boolean hasActiveWorkerForQueue(String id) {
        String queueId = WorkerQueues.normalize(id);
        if (WorkerQueues.DEFAULT_ID.equals(queueId) || WorkerQueues.SYSTEM_ID.equals(queueId)) {
            return true;
        }
        if (configuration.groups().isEmpty()) {
            return configuration.queues().containsKey(queueId);
        }
        return subscribedQueueIds().contains(queueId);
    }

    @Override
    public Set<String> listAllWorkerQueueIds() {
        return Stream.concat(configuration.queues().keySet().stream(), subscribedQueueIds().stream())
            .filter(queueId -> !WorkerQueues.DEFAULT_ID.equals(queueId))
            .filter(queueId -> !WorkerQueues.SYSTEM_ID.equals(queueId))
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public List<String> resolveQueueIdsByTags(Set<String> requiredTags, String tenant, WorkerSelectorMatch match) {
        if (requiredTags == null || requiredTags.isEmpty()) {
            return List.of();
        }

        Set<String> required = normalizeTags(requiredTags);
        Set<String> subscribed = subscribedQueueIds();
        WorkerSelectorMatch effectiveMatch = match == null ? WorkerSelectorMatch.ALL : match;

        return configuration.queues().entrySet().stream()
            .filter(entry -> tenantAllowed(entry.getValue(), tenant))
            .filter(entry -> tagsMatch(required, normalizeTags(entry.getValue().tags()), effectiveMatch))
            .sorted(Comparator
                .<Map.Entry<String, WorkerRoutingConfiguration.WorkerQueue>>comparingInt(entry -> subscribed.contains(entry.getKey()) ? 0 : 1)
                .thenComparingInt(entry -> extraTagCount(required, normalizeTags(entry.getValue().tags())))
                .thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .toList();
    }

    private Set<String> subscribedQueueIds() {
        if (configuration.groups().isEmpty()) {
            return configuration.queues().keySet().stream()
                .map(WorkerQueues::normalize)
                .collect(Collectors.toUnmodifiableSet());
        }
        return configuration.groups().values().stream()
            .flatMap(group -> group.queues().stream())
            .map(QueueSubscription::workerQueueId)
            .map(WorkerQueues::normalize)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean tenantAllowed(WorkerRoutingConfiguration.WorkerQueue queue, String tenant) {
        return queue.tenants().isEmpty() || queue.tenants().contains(tenant);
    }

    private static boolean tagsMatch(Set<String> required, Set<String> available, WorkerSelectorMatch match) {
        return switch (match) {
            case ALL -> available.containsAll(required);
            case ANY -> required.stream().anyMatch(available::contains);
        };
    }

    private static int extraTagCount(Set<String> required, Set<String> available) {
        return (int) available.stream()
            .filter(tag -> !required.contains(tag))
            .count();
    }

    private static Set<String> normalizeTags(Iterable<String> tags) {
        if (tags == null) {
            return Set.of();
        }
        return StreamSupport.stream(tags.spliterator(), false)
            .filter(tag -> tag != null && !tag.isBlank())
            .map(tag -> tag.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }
}
