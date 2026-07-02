package io.kestra.core.worker;

import java.util.function.Supplier;

import io.kestra.core.metrics.MetricRegistry;

import io.micronaut.context.annotation.Context;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes process-local observability for the static OSS worker routing table.
 */
@Slf4j
@Context
@Singleton
public class WorkerRoutingConfigurationObservability {
    @Inject
    public WorkerRoutingConfigurationObservability(WorkerRoutingConfiguration configuration, MetricRegistry metricRegistry) {
        if (!configuration.isRoutingConfigured()) {
            return;
        }

        String fingerprint = configuration.routingTableFingerprint();
        log.info(
            "Static worker routing configuration loaded: fingerprint={}, workerQueueCount={}, workerGroupCount={}",
            fingerprint,
            configuration.configuredWorkerQueueIds().size(),
            configuration.groupQueueMappings().size()
        );
        metricRegistry.gauge(
            MetricRegistry.METRIC_WORKER_ROUTING_CONFIGURATION,
            MetricRegistry.METRIC_WORKER_ROUTING_CONFIGURATION_DESCRIPTION,
            (Supplier<Integer>) () -> 1,
            MetricRegistry.TAG_ROUTING_FINGERPRINT,
            fingerprint
        );
    }
}
