package io.kestra.controller.grpc.services;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.worker.QueueSubscription;
import io.kestra.core.worker.WorkerQueues;
import io.kestra.core.worker.WorkerRoutingConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredWorkerQueueResolverTest {

    @Test
    void shouldResolveConfiguredGroupSubscriptions() {
        ConfiguredWorkerQueueResolver resolver = new ConfiguredWorkerQueueResolver(new WorkerRoutingConfiguration(
            null,
            Map.of("gce-a", new WorkerRoutingConfiguration.WorkerGroup(List.of(new QueueSubscription("gpu", 50)))),
            Map.of()
        ));

        List<QueueSubscription> result = resolver.resolve("gce-a");

        assertThat(result).containsExactly(new QueueSubscription("gpu", 50));
    }

    @Test
    void shouldResolveGroupToSameNamedQueueWhenOnlyQueueIsConfigured() {
        ConfiguredWorkerQueueResolver resolver = new ConfiguredWorkerQueueResolver(new WorkerRoutingConfiguration(
            null,
            Map.of(),
            Map.of("gce-a", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()))
        ));

        List<QueueSubscription> result = resolver.resolve("gce-a");

        assertThat(result).containsExactly(new QueueSubscription("gce-a", QueueSubscription.NO_RESERVATION));
    }

    @Test
    void shouldFallbackToDefaultSubscriptionForUnknownGroup() {
        ConfiguredWorkerQueueResolver resolver = new ConfiguredWorkerQueueResolver(new WorkerRoutingConfiguration(null, Map.of(), Map.of()));

        List<QueueSubscription> result = resolver.resolve("unknown");

        assertThat(result).containsExactly(QueueSubscription.DEFAULT);
    }

    @Test
    void shouldPreserveSystemQueueSubscription() {
        ConfiguredWorkerQueueResolver resolver = new ConfiguredWorkerQueueResolver(new WorkerRoutingConfiguration(null, Map.of(), Map.of()));

        List<QueueSubscription> result = resolver.resolve(WorkerQueues.SYSTEM_ID);

        assertThat(result).containsExactly(new QueueSubscription(WorkerQueues.SYSTEM_ID, QueueSubscription.NO_RESERVATION));
    }
}
