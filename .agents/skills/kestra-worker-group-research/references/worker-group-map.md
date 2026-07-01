# Kestra OSS Worker Group Routing Map

## Commit Lineage

Use these commits to understand what tacogips added on `main`:

- `f64927b52 feat(worker): add static OSS worker routing`
  - Adds `WorkerRoutingConfiguration`, `ConfiguredWorkerQueueMetaStore`, `ConfiguredWorkerQueueService`, `ConfiguredWorkerQueueResolver`, gRPC worker group handshake fields, docs, and focused tests.
- `7cc6925f9 fix(worker): introspect routing configuration records`
  - Ensures Micronaut can bind nested routing records.
- `b1661a49b docs: clarify OSS worker routing design`
- `527a2078f docs(worker): document OSS worker group routing`
- `98b507806 docs(worker): distinguish OSS and fork routing sequences`
- `8b621158c docs(worker): document routing database impact`
- `3d6563d3a fix(worker): keep routing indices consistent on reconnect`
  - Updates `WorkerJobDispatcher` indexing for reconnect and worker group subscription changes.

## Mental Model

The OSS fork does not implement full Enterprise Worker Groups. It adds static, configuration-backed routing:

```text
worker config workerGroupId
  -> worker connects to controller
  -> controller resolves group subscriptions to Worker Queue ids
  -> executor resolves workerSelector.tags to one Worker Queue id
  -> keyed worker job dispatch
  -> connected workers subscribed to that queue execute the job
```

There is no controller-to-worker inbound SSH/HTTP callback. Workers connect outbound to the controller and shared backend.

## Configuration

Controller-side services need the full routing table:

```yaml
kestra:
  worker:
    routing:
      groupQueueMappings:
        gce-gpu:
          queues:
            - workerQueueId: gce-gpu
              reservedPercent: -1
      queues:
        gce-gpu:
          tags: [gce, gpu]
```

Each worker process sets only its own group id:

```yaml
kestra:
  worker:
    routing:
      workerGroupId: gce-gpu
```

If `workerGroupId` is absent or empty, `WorkerGroups.normalize()` maps it to `default`.

If `groupQueueMappings` is omitted and a worker requests a group id matching a configured queue id, `ConfiguredWorkerQueueResolver` subscribes that worker to the same-named queue.

Every `workerQueueId` referenced from `groupQueueMappings.*.queues` must either be configured under `queues` or be one of the reserved queues (`default` or `system`). Configuration loading fails when a mapping points at an undefined Worker Queue.

If `kestra.worker.routing.queues` is empty or absent, static routing is disabled and jobs keep upstream OSS default queue behavior.

## Worker-Side Path

- `WorkerRoutingConfiguration` binds `kestra.worker.routing`.
- `GrpcWorkerConnectionService.connect()` sends `requestedWorkerGroupId` from `workerRoutingConfiguration.workerGroupId()`, normalized by `WorkerGroups.normalize()`.
- `GrpcConnectControllerService.resolveWorkerGroupId()` accepts and normalizes the requested id in OSS.
- `WorkerAgent.resolveWorkerGroupId()` stores the resolved group id returned by the controller.
- `WorkerJobFetcher.sendInitialRequest()` includes `WorkerConnectionInfo.workerGroupId` when opening the job stream.
- `AbstractWorker` and worker processors carry `workerGroupId` into `WorkerContext` and metrics.

Important: the worker does not decide task eligibility by inspecting tags. It advertises a group id; the controller subscribes that stream to queues.

## Controller-Side Subscription Path

`ConfiguredWorkerQueueResolver.resolve(workerGroupId)` maps group id to subscriptions:

1. Normalize group id.
2. If `configuration.groupQueueMappings()[groupId].queues` exists and is non-empty, return those subscriptions.
3. Else if `configuration.queues()` contains `groupId`, subscribe to the same-named queue.
4. Else if `groupId == system`, subscribe to the system queue.
5. Else subscribe to the default queue.

`WorkerJobDispatcher` maintains worker indices by worker group and worker queue. The reconnect fix in `3d6563d3a` matters when the same worker id reconnects with a different group or when subscriptions are synchronized.

## Executor Routing Path

`ConfiguredWorkerQueueService.doResolveWorkerQueueForJob(flow, workerJob)`:

1. Return empty if static routing is not configured.
2. Select the effective `WorkerSelector`:
   - task-level selector overrides flow-level selector;
   - trigger-level selector overrides flow-level selector;
   - otherwise use flow-level selector.
3. Return empty if no selector tags are present.
4. Resolve queue ids by tags through `WorkerQueueMetaStore`.
5. If no queue matches:
   - `fallback: IGNORE` routes to the default queue;
   - otherwise throw `NoMatchingWorkerQueueException`.
6. If a queue matches and has an active/configured worker, dispatch to that queue.
7. If a queue matches but no worker is available, use fallback:
   - `WAIT` -> wait and dispatch later;
   - `CANCEL` -> cancel;
   - `IGNORE` -> default queue;
   - `FAIL` or null -> fail.

System tasks bypass this path and route to the `system` queue.

## Tag Matching Rules

`ConfiguredWorkerQueueMetaStore.resolveQueueIdsByTags()`:

- normalizes selector and queue tags to lowercase;
- `match: ALL` means queue tags must contain every selector tag;
- `match: ANY` means queue tags need at least one selector tag;
- queue `tenants` restrict matching to listed tenants;
- subscribed queues are preferred;
- fewer extra queue tags wins;
- queue id is the stable tie breaker.

## Common Wrong Assumptions To Avoid

- Do not say worker group is chosen automatically from host labels, CPU/GPU, Kubernetes labels, or environment unless code/config proves it. In this fork it is configured via `kestra.worker.routing.workerGroupId`.
- Do not describe OSS routing as persisted or UI-managed Worker Groups. That is Enterprise behavior, not this static OSS layer.
- Do not tell users to set a flow `workerGroup` field for this OSS routing path. The entry point is `workerSelector.tags`.
- Do not imply non-target workers receive and skip jobs. Routing happens before dispatch to a keyed queue.
- Do not use the old `groups` configuration key. The routing table field is `groupQueueMappings` because it maps Worker Groups to Worker Queue subscriptions.
- Do not rely on the Helm chart `--worker-group` flag without checking CLI support in this checkout. The robust OSS config path is `kestra.worker.routing.workerGroupId`.

## Useful Searches

```bash
rg -n "workerGroupId|WorkerGroups|WorkerRoutingConfiguration|ConfiguredWorkerQueue|workerSelector|QueueSubscription" \
  core worker worker-controller executor docs README.md

rg -n "resolveWorkerQueueForJob|resolveQueueIdsByTags|hasActiveWorkerForQueue|resolve\\(String workerGroupId\\)" \
  core/src/main/java worker-controller/src/main/java

rg -n "worker group|workerGroup|workerSelector" \
  README.md docs core/src/test worker-controller/src/test worker/src/test
```

## Focused Tests

```bash
./gradlew :core:test --tests "io.kestra.core.services.WorkerQueueServiceTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.ConfiguredWorkerQueueResolverTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.GrpcConnectControllerServiceTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.WorkerJobDispatcherTest"
```
