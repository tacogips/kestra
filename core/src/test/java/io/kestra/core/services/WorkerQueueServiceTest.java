package io.kestra.core.services;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.kestra.core.exceptions.NoMatchingWorkerQueueException;
import io.kestra.core.models.executions.TaskRun;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.FlowInterface;
import io.kestra.core.models.tasks.WorkerQueueFallback;
import io.kestra.core.models.tasks.WorkerSelector;
import io.kestra.core.models.tasks.WorkerSelectorMatch;
import io.kestra.core.runners.ConfiguredWorkerQueueMetaStore;
import io.kestra.core.runners.WorkerQueueRouting;
import io.kestra.core.runners.WorkerTask;
import io.kestra.core.runners.WorkerTrigger;
import io.kestra.core.runners.WorkerTriggerData;
import io.kestra.core.worker.QueueSubscription;
import io.kestra.core.worker.WorkerQueues;
import io.kestra.core.worker.WorkerRoutingConfiguration;
import io.kestra.plugin.core.debug.Return;
import io.kestra.plugin.core.log.PurgeLogs;
import io.kestra.plugin.core.trigger.Schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerQueueServiceTest {

    private final WorkerQueueService service = new WorkerQueueService.Default();

    @Test
    void shouldRouteSystemTaskToSystemQueueWhenWorkerJobIsSystemTask() throws NoMatchingWorkerQueueException {
        WorkerTask workerTask = WorkerTask.builder()
            .task(PurgeLogs.builder().id("purge").type(PurgeLogs.class.getName()).build())
            .taskRun(TaskRun.builder().build())
            .build();

        Optional<WorkerQueueRouting> result = service.resolveWorkerQueueForJob(buildFlow(), workerTask);

        assertThat(result).isPresent();
        assertThat(result.get().isSystem()).isTrue();
        assertThat(result.get().workerQueueId()).isEqualTo(WorkerQueues.SYSTEM_ID);
    }

    @Test
    void shouldRouteSystemTaskToSystemQueueWhenSystemTaskCarriesWorkerSelectorTags() throws NoMatchingWorkerQueueException {
        WorkerTask workerTask = WorkerTask.builder()
            .task(PurgeLogs.builder()
                .id("purge")
                .type(PurgeLogs.class.getName())
                .workerSelector(new WorkerSelector(List.of("docker"), null))
                .build())
            .taskRun(TaskRun.builder().build())
            .build();

        Optional<WorkerQueueRouting> result = service.resolveWorkerQueueForJob(buildFlow(), workerTask);

        assertThat(result).isPresent();
        assertThat(result.get().isSystem()).isTrue();
    }

    @Test
    void shouldDelegateToDoResolveWhenWorkerJobIsRegularTask() throws NoMatchingWorkerQueueException {
        WorkerTask workerTask = WorkerTask.builder()
            .task(Return.builder().id("return").build())
            .taskRun(TaskRun.builder().build())
            .build();

        // Noop returns empty for non-SystemTask jobs.
        assertThat(service.resolveWorkerQueueForJob(buildFlow(), workerTask)).isEmpty();
    }

    @Test
    void shouldDelegateToDoResolveWhenWorkerJobIsTrigger() throws NoMatchingWorkerQueueException {
        WorkerTrigger workerTrigger = WorkerTrigger.builder()
            .trigger(Schedule.builder().id("trigger").build())
            .data(new WorkerTriggerData(
                "tenant", "ns", "flow", null, null, null, null,
                Collections.emptyMap(), null, null, Collections.emptyMap()))
            .build();

        // Triggers cannot be SystemTasks, so the short-circuit does not apply.
        assertThat(service.resolveWorkerQueueForJob(buildFlow(), workerTrigger)).isEmpty();
    }

    @Test
    void shouldRouteTaskSelectorToConfiguredWorkerQueue() throws NoMatchingWorkerQueueException {
        WorkerTask workerTask = WorkerTask.builder()
            .task(Return.builder()
                .id("return")
                .workerSelector(new WorkerSelector(List.of("gpu"), null))
                .build())
            .taskRun(TaskRun.builder().build())
            .build();

        Optional<WorkerQueueRouting> result = configuredService().resolveWorkerQueueForJob(buildFlow(), workerTask);

        assertThat(result).isPresent();
        assertThat(result.get().workerQueueId()).isEqualTo("gce-a");
        assertThat(result.get().disposition()).isEqualTo(WorkerQueueRouting.Disposition.DISPATCH);
    }

    @Test
    void shouldIgnoreWorkerSelectorWhenStaticRoutingIsNotConfigured() throws NoMatchingWorkerQueueException {
        WorkerTask workerTask = WorkerTask.builder()
            .task(Return.builder()
                .id("return")
                .workerSelector(new WorkerSelector(List.of("gpu"), null))
                .build())
            .taskRun(TaskRun.builder().build())
            .build();
        WorkerRoutingConfiguration emptyConfig = new WorkerRoutingConfiguration(null, Map.of(), Map.of());
        WorkerQueueService noRoutingService = new ConfiguredWorkerQueueService(
            new ConfiguredWorkerQueueMetaStore(emptyConfig),
            emptyConfig
        );

        assertThat(noRoutingService.resolveWorkerQueueForJob(buildFlow(), workerTask)).isEmpty();
    }

    @Test
    void shouldRouteToConfiguredQueueWithoutExplicitGroup() throws NoMatchingWorkerQueueException {
        WorkerTask workerTask = WorkerTask.builder()
            .task(Return.builder()
                .id("return")
                .workerSelector(new WorkerSelector(List.of("gpu"), null))
                .build())
            .taskRun(TaskRun.builder().build())
            .build();
        WorkerRoutingConfiguration config = new WorkerRoutingConfiguration(
            null,
            Map.of(),
            Map.of("gce-a", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()))
        );
        WorkerQueueService queueOnlyService = new ConfiguredWorkerQueueService(
            new ConfiguredWorkerQueueMetaStore(config),
            config
        );

        Optional<WorkerQueueRouting> result = queueOnlyService.resolveWorkerQueueForJob(buildFlow(), workerTask);

        assertThat(result).isPresent();
        assertThat(result.get().workerQueueId()).isEqualTo("gce-a");
        assertThat(result.get().disposition()).isEqualTo(WorkerQueueRouting.Disposition.DISPATCH);
    }

    @Test
    void shouldApplyFallbackWhenQueueHasNoConfiguredSubscriber() throws NoMatchingWorkerQueueException {
        WorkerTask workerTask = WorkerTask.builder()
            .task(Return.builder()
                .id("return")
                .workerSelector(new WorkerSelector(List.of("gpu"), WorkerQueueFallback.CANCEL))
                .build())
            .taskRun(TaskRun.builder().build())
            .build();
        WorkerRoutingConfiguration config = new WorkerRoutingConfiguration(
            null,
            Map.of("other", new WorkerRoutingConfiguration.WorkerGroup(List.of(new QueueSubscription("other", QueueSubscription.NO_RESERVATION)))),
            Map.of("gce-a", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu"), List.of()))
        );
        WorkerQueueService serviceWithNoSubscriber = new ConfiguredWorkerQueueService(
            new ConfiguredWorkerQueueMetaStore(config),
            config
        );

        Optional<WorkerQueueRouting> result = serviceWithNoSubscriber.resolveWorkerQueueForJob(buildFlow(), workerTask);

        assertThat(result).isPresent();
        assertThat(result.get().workerQueueId()).isEqualTo("gce-a");
        assertThat(result.get().disposition()).isEqualTo(WorkerQueueRouting.Disposition.CANCEL);
    }

    @Test
    void shouldLetTaskSelectorOverrideFlowSelector() throws NoMatchingWorkerQueueException {
        FlowInterface flow = Flow.builder()
            .id("flow")
            .namespace("namespace")
            .tenantId("tenant")
            .workerSelector(new WorkerSelector(List.of("cpu"), null))
            .build();
        WorkerTask workerTask = WorkerTask.builder()
            .task(Return.builder()
                .id("return")
                .workerSelector(new WorkerSelector(List.of("gpu"), null))
                .build())
            .taskRun(TaskRun.builder().build())
            .build();

        Optional<WorkerQueueRouting> result = configuredService().resolveWorkerQueueForJob(flow, workerTask);

        assertThat(result).isPresent();
        assertThat(result.get().workerQueueId()).isEqualTo("gce-a");
    }

    @Test
    void shouldRouteTriggerSelectorToConfiguredWorkerQueue() throws NoMatchingWorkerQueueException {
        WorkerTrigger workerTrigger = WorkerTrigger.builder()
            .trigger(Schedule.builder()
                .id("trigger")
                .workerSelector(new WorkerSelector(List.of("batch"), WorkerSelectorMatch.ANY, null))
                .build())
            .data(new WorkerTriggerData(
                "tenant", "ns", "flow", null, null, null, null,
                Collections.emptyMap(), null, null, Collections.emptyMap()))
            .build();

        Optional<WorkerQueueRouting> result = configuredService().resolveWorkerQueueForJob(buildFlow(), workerTrigger);

        assertThat(result).isPresent();
        assertThat(result.get().workerQueueId()).isEqualTo("gce-b");
    }

    @Test
    void shouldRouteToDefaultWhenFallbackIgnoreHasNoMatch() throws NoMatchingWorkerQueueException {
        WorkerTask workerTask = WorkerTask.builder()
            .task(Return.builder()
                .id("return")
                .workerSelector(new WorkerSelector(List.of("missing"), WorkerQueueFallback.IGNORE))
                .build())
            .taskRun(TaskRun.builder().build())
            .build();

        Optional<WorkerQueueRouting> result = configuredService().resolveWorkerQueueForJob(buildFlow(), workerTask);

        assertThat(result).isPresent();
        assertThat(result.get().isDefault()).isTrue();
    }

    @Test
    void shouldFailWhenSelectorHasNoConfiguredQueueMatch() {
        WorkerTask workerTask = WorkerTask.builder()
            .task(Return.builder()
                .id("return")
                .workerSelector(new WorkerSelector(List.of("missing"), null))
                .build())
            .taskRun(TaskRun.builder().build())
            .build();

        assertThatThrownBy(() -> configuredService().resolveWorkerQueueForJob(buildFlow(), workerTask))
            .isInstanceOf(NoMatchingWorkerQueueException.class)
            .hasMessageContaining("missing");
    }

    private FlowInterface buildFlow() {
        return Flow.builder()
            .id("flow")
            .namespace("namespace")
            .tenantId("tenant")
            .build();
    }

    private WorkerQueueService configuredService() {
        WorkerRoutingConfiguration config = config();
        return new ConfiguredWorkerQueueService(new ConfiguredWorkerQueueMetaStore(config), config);
    }

    private WorkerRoutingConfiguration config() {
        return new WorkerRoutingConfiguration(
            null,
            Map.of(
                "gce-a", new WorkerRoutingConfiguration.WorkerGroup(List.of(new QueueSubscription("gce-a", QueueSubscription.NO_RESERVATION))),
                "gce-b", new WorkerRoutingConfiguration.WorkerGroup(List.of(new QueueSubscription("gce-b", QueueSubscription.NO_RESERVATION)))
            ),
            Map.of(
                "gce-a", new WorkerRoutingConfiguration.WorkerQueue(List.of("gpu", "gce-a"), List.of()),
                "gce-b", new WorkerRoutingConfiguration.WorkerQueue(List.of("cpu", "batch", "gce-b"), List.of())
            )
        );
    }
}
