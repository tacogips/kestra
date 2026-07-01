package io.kestra.core.worker;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.micronaut.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerRoutingConfigurationTest {

    @Test
    void shouldRejectGroupQueueMappingReferencingUndefinedQueue() {
        assertThatThrownBy(
            () -> new WorkerRoutingConfiguration(
                null,
                Map.of(
                    "gce-gpu-workers", new WorkerRoutingConfiguration.GroupQueueMapping(
                        List.of(
                            new QueueSubscription("not-exists-queue", QueueSubscription.NO_RESERVATION)
                        )
                    )
                ),
                Map.of("gpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()))
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("gce-gpu-workers -> not-exists-queue");
    }

    @Test
    void shouldAllowGroupQueueMappingReferencingConfiguredQueue() {
        WorkerRoutingConfiguration configuration = new WorkerRoutingConfiguration(
            null,
            Map.of(
                "gce-gpu-workers", new WorkerRoutingConfiguration.GroupQueueMapping(
                    List.of(
                        new QueueSubscription("gpu-jobs", QueueSubscription.NO_RESERVATION)
                    )
                )
            ),
            Map.of("gpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()))
        );

        assertThat(configuration.groupQueueMappings()).containsKey("gce-gpu-workers");
    }

    @Test
    void shouldBindGroupQueueMappingsFromConfigurationProperties() {
        try (
            ApplicationContext ctx = ApplicationContext.run(
                Map.of(
                    "kestra.worker.routing.group-queue-mappings.gce-gpu-workers.queues[0].worker-queue-id", "gpu-jobs",
                    "kestra.worker.routing.group-queue-mappings.gce-gpu-workers.queues[0].reserved-percent", "-1",
                    "kestra.worker.routing.queues.gpu-jobs.tags[0]", "gpu"
                )
            )
        ) {
            WorkerRoutingConfiguration configuration = ctx.getBean(WorkerRoutingConfiguration.class);

            assertThat(configuration.groupQueueMappings()).containsKey("gce-gpu-workers");
            assertThat(configuration.groupQueueMappings().get("gce-gpu-workers").queues())
                .containsExactly(new QueueSubscription("gpu-jobs", QueueSubscription.NO_RESERVATION));
            assertThat(configuration.queues()).containsKey("gpu-jobs");
        }
    }

    @Test
    void shouldRejectUndefinedQueueFromConfigurationProperties() {
        assertThatThrownBy(() ->
        {
            try (
                ApplicationContext ignored = ApplicationContext.run(
                    Map.of(
                        "kestra.worker.routing.group-queue-mappings.gce-gpu-workers.queues[0].worker-queue-id", "not-exists-queue",
                        "kestra.worker.routing.group-queue-mappings.gce-gpu-workers.queues[0].reserved-percent", "-1",
                        "kestra.worker.routing.queues.gpu-jobs.tags[0]", "gpu"
                    )
                )
            ) {
                ignored.getBean(WorkerRoutingConfiguration.class);
            }
        })
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not-exists-queue");
    }
}
