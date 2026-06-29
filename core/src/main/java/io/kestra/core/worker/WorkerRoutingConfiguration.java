package io.kestra.core.worker;

import java.util.List;
import java.util.Map;

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
    @Valid @Nullable Map<String, WorkerGroup> groups,
    @Valid @Nullable Map<String, WorkerQueue> queues
) {

    public WorkerRoutingConfiguration {
        groups = groups == null ? Map.of() : Map.copyOf(groups);
        queues = queues == null ? Map.of() : Map.copyOf(queues);
    }

    /**
     * @return {@code true} when static queue definitions are configured.
     */
    public boolean isRoutingConfigured() {
        return !queues.isEmpty();
    }

    /**
     * Static worker group definition. A worker in this group subscribes to these
     * Worker Queues. Empty subscriptions are resolved by the controller adapter.
     */
    @Introspected
    public record WorkerGroup(@Valid @Nullable List<QueueSubscription> queues) {
        public WorkerGroup {
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
