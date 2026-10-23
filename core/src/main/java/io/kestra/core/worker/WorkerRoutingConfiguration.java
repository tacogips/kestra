package io.kestra.core.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /**
     * Returns every Worker Queue id declared by the static routing table or a
     * configured Worker Group subscription.
     *
     * @return normalized Worker Queue ids
     */
    public Set<String> configuredWorkerQueueIds() {
        return Stream.concat(
            queues.keySet().stream(),
            groupQueueMappings.values().stream()
                .flatMap(group -> group.queues().stream())
                .map(QueueSubscription::workerQueueId)
        )
            .map(WorkerQueues::normalize)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Returns a deterministic fingerprint for the routing table shared by
     * executor, scheduler, and controller processes.
     *
     * @return SHA-256 fingerprint of the canonical routing table
     */
    public String routingTableFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalRoutingTable().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }

    private String canonicalRoutingTable() {
        StringBuilder builder = new StringBuilder();
        builder.append("queues:");
        queues.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(
                entry -> builder
                    .append(WorkerQueues.normalize(entry.getKey()))
                    .append("[tags=")
                    .append(normalizedTags(entry.getValue().tags()))
                    .append(",tenants=")
                    .append(new TreeSet<>(entry.getValue().tenants()))
                    .append("];")
            );

        builder.append("groups:");
        groupQueueMappings.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry ->
            {
                builder.append(WorkerGroups.normalize(entry.getKey())).append("[");
                entry.getValue().queues().stream()
                    .sorted(WorkerRoutingConfiguration::compareSubscriptions)
                    .forEach(
                        subscription -> builder
                            .append(WorkerQueues.normalize(subscription.workerQueueId()))
                            .append(":")
                            .append(subscription.reservedPercent())
                            .append(":")
                            .append(subscription.mode())
                            .append(";")
                    );
                builder.append("];");
            });
        return builder.toString();
    }

    private static int compareSubscriptions(QueueSubscription left, QueueSubscription right) {
        int byQueueId = WorkerQueues.normalize(left.workerQueueId()).compareTo(WorkerQueues.normalize(right.workerQueueId()));
        if (byQueueId != 0) {
            return byQueueId;
        }
        int byReservedPercent = Integer.compare(left.reservedPercent(), right.reservedPercent());
        if (byReservedPercent != 0) {
            return byReservedPercent;
        }
        return left.mode().compareTo(right.mode());
    }

    private static Set<String> normalizedTags(List<String> tags) {
        return tags.stream()
            .filter(tag -> tag != null && !tag.isBlank())
            .map(tag -> tag.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(TreeSet::new));
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

        List<String> overReservedGroups = groupQueueMappings.entrySet().stream()
            .map(entry ->
            {
                int reservedPercentTotal = entry.getValue().queues().stream()
                    .mapToInt(QueueSubscription::reservedPercent)
                    .filter(reservedPercent -> reservedPercent > 0)
                    .sum();
                return Map.entry(entry.getKey(), reservedPercentTotal);
            })
            .filter(entry -> entry.getValue() > 100)
            .map(entry -> entry.getKey() + " -> " + entry.getValue())
            .toList();

        if (!overReservedGroups.isEmpty()) {
            throw new IllegalArgumentException(
                "kestra.worker.routing.groupQueueMappings reservedPercent totals must be <= 100: " + overReservedGroups
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
