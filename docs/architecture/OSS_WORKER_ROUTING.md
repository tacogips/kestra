# OSS Worker Routing

This fork adds static, configuration-backed worker routing for OSS deployments.
It is intentionally smaller than Enterprise Worker Groups: there is no UI, no
repository-backed group management, and no dynamic policy API. Operators define
worker groups and worker queues in each Kestra process configuration.

The goal is deterministic routing without changing the flow shape between local,
staging, and production:

- controller-side services can run in GKE without running a worker there;
- GCE or on-prem machines run Kestra in `worker` mode only;
- each worker process advertises its configured group id when it connects;
- the controller subscribes that worker to the configured queue ids;
- the executor routes tasks and triggers with `workerSelector.tags` to one of
  those queues before dispatch.

Because routing happens before dispatch, non-target workers do not execute a
skip/no-op task. There is no skip task run and no skip log to filter from the
execution view.

## Implementation Map

The implementation deliberately reuses Kestra's existing worker queue model
instead of introducing a second dispatch path:

- `WorkerRoutingConfiguration` binds the static
  `kestra.worker.routing` configuration.
- `ConfiguredWorkerQueueMetaStore` exposes configured queue metadata to the
  executor. It resolves `workerSelector.tags` to queue ids, applies tenant
  filters, and treats explicitly subscribed queues as routable.
- `ConfiguredWorkerQueueService` selects the effective `workerSelector` for a
  task or trigger, resolves it to a queue, and returns the dispatch disposition
  used by the existing executor path.
- `GrpcWorkerConnectionService` sends the worker's configured
  `workerGroupId` when the worker connects to the controller.
- `GrpcConnectControllerService` normalizes the requested worker group id in
  OSS and returns it to the worker. Enterprise-specific authentication and
  repository-backed group resolution can still override this service.
- `ConfiguredWorkerQueueResolver` maps the resolved worker group id to queue
  subscriptions for the gRPC worker controller.

The practical result is:

```text
Flow task workerSelector.tags
  -> ConfiguredWorkerQueueService
  -> Worker Queue id
  -> keyed worker job dispatch
  -> connected workers whose group subscribes to that queue
```

No controller-to-worker SSH or HTTP callback is involved. Workers connect
outbound to the controller and backend, then consume the queues assigned to
their resolved group.

## Configuration

Controller-side services need the full routing table so the executor can map
`workerSelector.tags` to queue ids and the worker controller can map group ids to
queue subscriptions:

```yaml
kestra:
  worker:
    routing:
      groupQueueMappings:
        gce-gpu:
          queues:
            - workerQueueId: gce-gpu
              reservedPercent: -1
        gce-cpu:
          queues:
            - workerQueueId: gce-cpu
              reservedPercent: -1
        onprem-private:
          queues:
            - workerQueueId: onprem-private
              reservedPercent: -1
      queues:
        gce-gpu:
          tags: [gce, gpu]
        gce-cpu:
          tags: [gce, cpu]
        onprem-private:
          tags: [onprem, private]
```

Each worker process sets its own group id. For example, a GPU GCE worker uses:

```yaml
kestra:
  worker:
    routing:
      workerGroupId: gce-gpu
```

An on-prem worker in a private network uses:

```yaml
kestra:
  worker:
    routing:
      workerGroupId: onprem-private
```

The worker still connects outbound to the Kestra controller and shared backend.
The controller does not need inbound SSH or HTTP access to the private worker
machine.

If `kestra.worker.routing.queues` is empty or absent, static routing is disabled
and `workerSelector` keeps the upstream OSS behavior. This prevents an ordinary
OSS deployment from changing behavior just because the forked classes are on the
classpath.

For a compact deployment, an explicit `groupQueueMappings` block is optional. If
a worker requests a group id that has the same name as a configured queue id, the
controller subscribes that worker to the same-named queue:

```yaml
kestra:
  worker:
    routing:
      workerGroupId: gce-gpu
      queues:
        gce-gpu:
          tags: [gce, gpu]
```

Every `workerQueueId` referenced from `groupQueueMappings.*.queues` must either
be a configured entry under `queues` or one of the reserved queues (`default` or
`system`). Kestra fails configuration loading when a mapping points at an
undefined Worker Queue.

## Flow Usage

The same flow resource can be deployed in local, staging, and production. The
environment decides which worker groups exist.

```yaml
id: routed_batch
namespace: company.batch

tasks:
  - id: gpu_job
    type: io.kestra.plugin.scripts.shell.Commands
    workerSelector:
      tags: [gpu]
      match: ALL
      fallback: FAIL
    commands:
      - ./run-gpu-job.sh

  - id: onprem_job
    type: io.kestra.plugin.scripts.shell.Commands
    workerSelector:
      tags: [onprem, private]
      match: ALL
      fallback: WAIT
    commands:
      - ./run-private-job.sh
```

Task-level `workerSelector` overrides the flow-level selector. Trigger-level
`workerSelector` also overrides the flow-level selector. If no selector is
present, the job uses the default worker queue.

Routing resolution is intentionally deterministic:

- tags are normalized to lowercase for matching;
- `match: ALL` requires every requested tag to be present on the queue;
- `match: ANY` requires at least one requested tag;
- queues restricted by `tenants` are ignored for other tenants;
- queues already subscribed by configured groups are preferred;
- when multiple queues still match, the queue with fewer extra tags is chosen,
  then the queue id is used as a stable tie breaker.

If no queue matches, `fallback: IGNORE` routes to the default queue. Other
fallbacks keep their normal meaning: `FAIL` fails routing, `CANCEL` cancels the
job, and `WAIT` waits for the selected queue to become available when a queue is
known but no matching worker is connected.

## Local And Staging

Local and staging can still run as a single-node or GKE-only environment by
providing a routing table that maps all required tags to queues served by the
available worker process. That keeps the flow YAML identical while changing only
deployment configuration.

For example, local can run one worker group that subscribes to every queue:

```yaml
kestra:
  worker:
    routing:
      workerGroupId: local-all
      groupQueueMappings:
        local-all:
          queues:
            - workerQueueId: gce-gpu
              reservedPercent: -1
            - workerQueueId: gce-cpu
              reservedPercent: -1
            - workerQueueId: onprem-private
              reservedPercent: -1
      queues:
        gce-gpu:
          tags: [gce, gpu]
        gce-cpu:
          tags: [gce, cpu]
        onprem-private:
          tags: [onprem, private]
```

Production can split those same queue ids across multiple GCE and on-prem
workers by changing only `workerGroupId` and group subscriptions.

## Limitations

- This is static OSS routing, not full Enterprise Worker Groups.
- Queue/group configuration is not managed from the UI.
- Live worker availability is enforced by the dispatcher and the connected
  worker streams; the static metadata store treats configured subscribed queues
  as routable.
- If a selected queue has no connected worker, the job remains waiting for a
  matching worker connection instead of being executed by a non-target worker.
- This does not replace task runners. A routed worker can still use `Process`,
  `Docker`, `Kubernetes`, or another task runner for supported task types.
- Runtime authorization is not added by this OSS layer. Deployments that allow
  remote workers to connect must still protect controller gRPC, database, and
  storage credentials at the network and secret-management layers.

## Verification

Run the focused tests that cover routing resolution and worker connection
behavior:

```bash
./gradlew :core:test --tests "io.kestra.core.services.WorkerQueueServiceTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.ConfiguredWorkerQueueResolverTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.GrpcConnectControllerServiceTest"
```

For a live or integration environment, verify the following operational
invariants:

- workers log the expected resolved `workerGroup`;
- controller-side worker queue metrics include the expected worker group and
  worker queue labels;
- a task with `workerSelector.tags` lands on the expected worker host;
- a selected queue with no connected worker waits, fails, cancels, or falls
  back according to the task's `fallback` policy;
- the controller host can observe execution state and rerun tasks without
  opening inbound access to private worker machines.

## Multi-Architecture Image

The runtime executable is architecture-independent Java bytecode, but the base
image must match the host CPU. The live playground deployment workflow checks
out this branch, builds the executable, installs the GCS storage plugin, and
publishes the routed image to Google Artifact Registry:

```text
<region>-docker.pkg.dev/<project-id>/kestra-playground/kestra-oss-worker-routing:oss-worker-routing
<region>-docker.pkg.dev/<project-id>/kestra-playground/kestra-oss-worker-routing:<playground-commit-sha>
<region>-docker.pkg.dev/<project-id>/kestra-playground/kestra-oss-worker-routing:oss-worker-routing-<kestra-commit-sha>
```

Manual builds can push the same manifest list with both supported platforms:

```bash
./gradlew writeExecutableJar
cp build/executable/kestra-2.0.0-SNAPSHOT docker/app/kestra
chmod 755 docker/app/kestra

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --provenance=false \
  -t "${REGION}-docker.pkg.dev/${PROJECT_ID}/kestra-playground/kestra-oss-worker-routing:oss-worker-routing" \
  --push \
  .
```

Use `linux/arm64` for Apple Silicon local containers and `linux/amd64` for the
default GKE node pool architecture. For local validation without registry push:

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --provenance=false \
  -t kestra-oss-worker-routing:local \
  --output type=oci,dest=/tmp/kestra-oss-worker-routing-multiarch.oci \
  .
```
