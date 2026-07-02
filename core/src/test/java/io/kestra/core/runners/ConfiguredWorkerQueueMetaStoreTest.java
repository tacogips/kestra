package io.kestra.core.runners;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kestra.core.models.tasks.WorkerSelectorMatch;
import io.kestra.core.worker.WorkerRoutingConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredWorkerQueueMetaStoreTest {

    @Test
    void shouldTreatConfiguredQueuesAsRoutableInCompactMode() {
        // Given
        ConfiguredWorkerQueueMetaStore metaStore = new ConfiguredWorkerQueueMetaStore(
            compactConfig()
        );

        // When
        boolean gpuRoutable = metaStore.hasActiveWorkerForQueue("gpu");
        boolean cpuRoutable = metaStore.hasActiveWorkerForQueue("cpu");
        boolean missingRoutable = metaStore.hasActiveWorkerForQueue("missing");

        // Then
        assertThat(gpuRoutable).isTrue();
        assertThat(cpuRoutable).isTrue();
        assertThat(missingRoutable).isFalse();
    }

    @Test
    void shouldResolveAllMatchingConfiguredQueuesAsSubscribedInCompactMode() {
        // Given
        ConfiguredWorkerQueueMetaStore metaStore = new ConfiguredWorkerQueueMetaStore(
            compactConfig()
        );

        // When
        List<String> queueIds = metaStore.resolveQueueIdsByTags(Set.of("gpu"), "tenant", WorkerSelectorMatch.ALL);

        // Then
        assertThat(queueIds).containsExactly("gpu", "gpu-large");
    }

    private WorkerRoutingConfiguration compactConfig() {
        return new WorkerRoutingConfiguration(
            null,
            Map.of(),
            Map.of(
                "gpu", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()),
                "gpu-large", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu", "large"), List.of()),
                "cpu", new WorkerRoutingConfiguration.WorkerQueue(List.of("cpu"), List.of())
            )
        );
    }
}
