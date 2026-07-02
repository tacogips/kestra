package io.kestra.controller.grpc.services;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.kestra.core.worker.QueueSubscription;
import io.kestra.core.worker.WorkerGroups;
import io.kestra.core.worker.WorkerQueues;
import io.kestra.core.worker.WorkerRoutingConfiguration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredWorkerQueueResolverTest {
    private ListAppender<ILoggingEvent> listAppender;
    private Logger resolverLogger;

    @AfterEach
    void tearDown() {
        if (resolverLogger != null && listAppender != null) {
            resolverLogger.detachAppender(listAppender);
        }
    }

    @Test
    void shouldResolveConfiguredGroupSubscriptions() {
        ConfiguredWorkerQueueResolver resolver = new ConfiguredWorkerQueueResolver(
            new WorkerRoutingConfiguration(
                null,
                Map.of("gce-a", new WorkerRoutingConfiguration.GroupQueueMapping(List.of(new QueueSubscription("gpu", 50)))),
                Map.of("gpu", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()))
            )
        );

        List<QueueSubscription> result = resolver.resolve("gce-a");

        assertThat(result).containsExactly(new QueueSubscription("gpu", 50));
    }

    @Test
    void shouldResolveGroupToSameNamedQueueWhenOnlyQueueIsConfigured() {
        ConfiguredWorkerQueueResolver resolver = new ConfiguredWorkerQueueResolver(
            new WorkerRoutingConfiguration(
                null,
                Map.of(),
                Map.of("gce-a", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()))
            )
        );

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
    void shouldWarnWhenUnknownCustomGroupFallsBackToDefaultSubscription() {
        // Given
        ConfiguredWorkerQueueResolver resolver = new ConfiguredWorkerQueueResolver(new WorkerRoutingConfiguration(null, Map.of(), Map.of()));
        ListAppender<ILoggingEvent> appender = captureResolverLogs();

        // When
        List<QueueSubscription> result = resolver.resolve("gce-gpuu");

        // Then
        assertThat(result).containsExactly(QueueSubscription.DEFAULT);
        assertThat(appender.list)
            .anySatisfy(event ->
            {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("Unknown worker group 'gce-gpuu' resolved to the default worker queue subscription");
            });
    }

    @Test
    void shouldNotWarnWhenDefaultGroupFallsBackToDefaultSubscription() {
        // Given
        ConfiguredWorkerQueueResolver resolver = new ConfiguredWorkerQueueResolver(new WorkerRoutingConfiguration(null, Map.of(), Map.of()));
        ListAppender<ILoggingEvent> appender = captureResolverLogs();

        // When
        List<QueueSubscription> result = resolver.resolve(WorkerGroups.DEFAULT_ID);

        // Then
        assertThat(result).containsExactly(QueueSubscription.DEFAULT);
        assertThat(appender.list).noneSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void shouldNotWarnWhenKnownOrSystemGroupResolvesWithoutDefaultFallback() {
        // Given
        ConfiguredWorkerQueueResolver resolver = new ConfiguredWorkerQueueResolver(
            new WorkerRoutingConfiguration(
                null,
                Map.of("gce-a", new WorkerRoutingConfiguration.GroupQueueMapping(List.of(new QueueSubscription("gpu", 50)))),
                Map.of(
                    "gpu", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()),
                    "gce-b", new WorkerRoutingConfiguration.WorkerQueue(List.of("cpu"), List.of())
                )
            )
        );
        ListAppender<ILoggingEvent> appender = captureResolverLogs();

        // When
        List<QueueSubscription> mappedGroup = resolver.resolve("gce-a");
        List<QueueSubscription> sameNamedQueue = resolver.resolve("gce-b");
        List<QueueSubscription> systemGroup = resolver.resolve(WorkerQueues.SYSTEM_ID);

        // Then
        assertThat(mappedGroup).containsExactly(new QueueSubscription("gpu", 50));
        assertThat(sameNamedQueue).containsExactly(new QueueSubscription("gce-b", QueueSubscription.NO_RESERVATION));
        assertThat(systemGroup).containsExactly(new QueueSubscription(WorkerQueues.SYSTEM_ID, QueueSubscription.NO_RESERVATION));
        assertThat(appender.list).noneSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void shouldPreserveSystemQueueSubscription() {
        ConfiguredWorkerQueueResolver resolver = new ConfiguredWorkerQueueResolver(new WorkerRoutingConfiguration(null, Map.of(), Map.of()));

        List<QueueSubscription> result = resolver.resolve(WorkerQueues.SYSTEM_ID);

        assertThat(result).containsExactly(new QueueSubscription(WorkerQueues.SYSTEM_ID, QueueSubscription.NO_RESERVATION));
    }

    private ListAppender<ILoggingEvent> captureResolverLogs() {
        resolverLogger = (Logger) LoggerFactory.getLogger(ConfiguredWorkerQueueResolver.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        resolverLogger.addAppender(listAppender);
        return listAppender;
    }
}
