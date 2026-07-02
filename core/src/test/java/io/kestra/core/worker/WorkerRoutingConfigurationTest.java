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
    void shouldRejectReservedPercentTotalGreaterThanOneHundred() {
        // Given / When / Then
        assertThatThrownBy(
            () -> new WorkerRoutingConfiguration(
                null,
                Map.of(
                    "balanced-workers", new WorkerRoutingConfiguration.GroupQueueMapping(
                        List.of(
                            new QueueSubscription("gpu-jobs", 60),
                            new QueueSubscription("cpu-jobs", 41),
                            new QueueSubscription("shared-jobs", QueueSubscription.NO_RESERVATION)
                        )
                    )
                ),
                Map.of(
                    "gpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()),
                    "cpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("cpu"), List.of()),
                    "shared-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("shared"), List.of())
                )
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reservedPercent totals must be <= 100")
            .hasMessageContaining("balanced-workers -> 101");
    }

    @Test
    void shouldAllowNoReservationEntriesAndReservedPercentTotalEqualToOneHundred() {
        // Given
        WorkerRoutingConfiguration configuration = new WorkerRoutingConfiguration(
            null,
            Map.of(
                "balanced-workers", new WorkerRoutingConfiguration.GroupQueueMapping(
                    List.of(
                        new QueueSubscription("gpu-jobs", 60, QueueSubscription.Mode.ELASTIC),
                        new QueueSubscription("cpu-jobs", 40),
                        new QueueSubscription("shared-jobs", QueueSubscription.NO_RESERVATION)
                    )
                )
            ),
            Map.of(
                "gpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()),
                "cpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("cpu"), List.of()),
                "shared-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("shared"), List.of())
            )
        );

        // When
        List<QueueSubscription> queues = configuration.groupQueueMappings().get("balanced-workers").queues();

        // Then
        assertThat(queues)
            .containsExactly(
                new QueueSubscription("gpu-jobs", 60, QueueSubscription.Mode.ELASTIC),
                new QueueSubscription("cpu-jobs", 40),
                new QueueSubscription("shared-jobs", QueueSubscription.NO_RESERVATION)
            );
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
    void shouldExposeConfiguredWorkerQueueIdsFromQueuesAndMappings() {
        // Given
        WorkerRoutingConfiguration configuration = new WorkerRoutingConfiguration(
            null,
            Map.of(
                "mixed-workers", new WorkerRoutingConfiguration.GroupQueueMapping(
                    List.of(
                        new QueueSubscription("gpu-jobs", QueueSubscription.NO_RESERVATION),
                        QueueSubscription.DEFAULT
                    )
                )
            ),
            Map.of(
                "cpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("cpu"), List.of()),
                "gpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of())
            )
        );

        // When / Then
        assertThat(configuration.configuredWorkerQueueIds())
            .containsExactly("cpu-jobs", "default", "gpu-jobs");
    }

    @Test
    void shouldCreateStableRoutingTableFingerprint() {
        // Given
        WorkerRoutingConfiguration first = new WorkerRoutingConfiguration(
            "worker-a",
            Map.of(
                "group-a", new WorkerRoutingConfiguration.GroupQueueMapping(
                    List.of(
                        new QueueSubscription("cpu-jobs", 20),
                        new QueueSubscription("gpu-jobs", QueueSubscription.NO_RESERVATION, QueueSubscription.Mode.ELASTIC)
                    )
                )
            ),
            Map.of(
                "gpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("GPU", "linux"), List.of("tenant-b", "tenant-a")),
                "cpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("cpu"), List.of())
            )
        );
        WorkerRoutingConfiguration sameRoutingTable = new WorkerRoutingConfiguration(
            "worker-b",
            Map.of(
                "group-a", new WorkerRoutingConfiguration.GroupQueueMapping(
                    List.of(
                        new QueueSubscription("gpu-jobs", QueueSubscription.NO_RESERVATION, QueueSubscription.Mode.ELASTIC),
                        new QueueSubscription("cpu-jobs", 20)
                    )
                )
            ),
            Map.of(
                "cpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("cpu"), List.of()),
                "gpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("linux", "gpu"), List.of("tenant-a", "tenant-b"))
            )
        );
        WorkerRoutingConfiguration changedRoutingTable = new WorkerRoutingConfiguration(
            "worker-a",
            first.groupQueueMappings(),
            Map.of(
                "gpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu", "linux"), List.of("tenant-a", "tenant-b")),
                "cpu-jobs", new WorkerRoutingConfiguration.WorkerQueue(List.of("cpu", "large"), List.of())
            )
        );

        // When / Then
        assertThat(first.routingTableFingerprint())
            .hasSize(64)
            .isEqualTo(sameRoutingTable.routingTableFingerprint())
            .isNotEqualTo(changedRoutingTable.routingTableFingerprint());
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

    @Test
    void shouldRejectReservedPercentTotalGreaterThanOneHundredFromConfigurationProperties() {
        assertThatThrownBy(() ->
        {
            try (
                ApplicationContext ignored = ApplicationContext.run(
                    Map.of(
                        "kestra.worker.routing.group-queue-mappings.balanced-workers.queues[0].worker-queue-id", "gpu-jobs",
                        "kestra.worker.routing.group-queue-mappings.balanced-workers.queues[0].reserved-percent", "60",
                        "kestra.worker.routing.group-queue-mappings.balanced-workers.queues[1].worker-queue-id", "cpu-jobs",
                        "kestra.worker.routing.group-queue-mappings.balanced-workers.queues[1].reserved-percent", "41",
                        "kestra.worker.routing.group-queue-mappings.balanced-workers.queues[2].worker-queue-id", "shared-jobs",
                        "kestra.worker.routing.group-queue-mappings.balanced-workers.queues[2].reserved-percent", "-1",
                        "kestra.worker.routing.queues.gpu-jobs.tags[0]", "gpu",
                        "kestra.worker.routing.queues.cpu-jobs.tags[0]", "cpu",
                        "kestra.worker.routing.queues.shared-jobs.tags[0]", "shared"
                    )
                )
            ) {
                ignored.getBean(WorkerRoutingConfiguration.class);
            }
        })
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("balanced-workers -> 101");
    }
}
