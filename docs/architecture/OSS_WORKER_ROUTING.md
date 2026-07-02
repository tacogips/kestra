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
outbound to the controller and backend. The controller owns the backend Worker
Job queue subscriptions for each Worker Queue and forwards matching jobs to
connected workers over the long-lived gRPC stream.

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
              reservedPercent: 100
              mode: STRICT
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

This compact form is a static same-name routing convenience. It is not live
worker availability detection: the executor-side `ConfiguredWorkerQueueMetaStore`
treats every configured queue as statically routable, while the controller
subscribes each connected worker only to the queue matching that worker's
requested group id. If no worker for that same-name queue is connected, jobs
remain queued until a matching worker connects.

Every `workerQueueId` referenced from `groupQueueMappings.*.queues` must either
be a configured entry under `queues` or one of the reserved queues (`default` or
`system`). Kestra fails configuration loading when a mapping points at an
undefined Worker Queue.

If a worker requests an unknown custom `workerGroupId`, the controller logs a
warning and subscribes that worker to the default Worker Queue. The reserved
`system` group maps to the system queue, and an absent or empty `workerGroupId`
normalizes to the default group.

`reservedPercent` controls the minimum percentage of a worker's slots reserved
for a queue subscription. Use `-1` (`QueueSubscription.NO_RESERVATION`) when a
queue should consume only shared, unreserved capacity. Positive values reserve
that percentage of capacity and must sum to at most `100` for each
`groupQueueMappings` entry; Kestra fails configuration loading when a group's
positive reservations exceed `100`. A total exactly equal to `100` is valid, and
`-1` entries do not contribute to the total.

`mode` controls how reserved slots are shared. `STRICT` is the default and keeps
the subscription's reserved slots exclusive. `ELASTIC` allows the subscription's
idle reserved slots to be borrowed by other subscriptions on the same worker.

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
- when multiple queues still match, they are ranked by fewer extra tags and then
  queue id as a stable tie breaker;
- the ranked candidates are checked in order, and dispatch uses the first queue
  the configured metadata store reports as active;
- if no ranked candidate is reported active, the selector fallback is applied to
  the best-ranked queue.

If no queue matches, `fallback: IGNORE` routes to the default queue. Other
fallbacks keep their normal meaning: `FAIL` fails routing, `CANCEL` cancels the
job, and `WAIT` waits for the selected queue to become available when a queue is
known but not reported active.

In this OSS implementation the configured metadata store is static process
configuration, not live worker presence. With explicit `groupQueueMappings`, a
queue is reported active when some configured group subscribes to it. In compact
mode, every configured queue is reported active as a same-name routing candidate.
That means `fallback` does not detect configured-but-unserved compact-mode
queues; the job is dispatched to the selected worker queue and remains queued
until a worker whose group subscribes to that queue connects.

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

## Observability

Controller processes expose queue-scoped gauges for every statically configured
Worker Queue, even before any worker has connected:

- `controller.worker.active{worker_queue="<id>"}`;
- `controller.permits.available{worker_queue="<id>"}`;
- `controller.job.inflight{worker_queue="<id>"}`.

These gauges report `0` for configured-but-unserved queues and remain registered
after the last worker disconnects. Dynamically observed, nonconfigured queue
gauges still keep the existing cleanup behavior and are removed when the last
worker disconnects. No extra queue subscriber is created just to publish the zero
gauges.

Use the configured queue gauges with backend queue lag to alert on stuck routed
work. The practical signal is:

```text
queue.message.lag{queue_name="<workerQueueId>"} > 0
and
controller.worker.active{worker_queue="<workerQueueId>"} == 0
```

The exact `queue_name` label value depends on the queue backend, but it should
identify the same Worker Queue key used by `worker_queue`. This alert detects a
configured queue accumulating jobs while no controller has an active worker for
that queue.

Every process that loads a static routing table also logs a deterministic
SHA-256 routing-table fingerprint at startup and publishes
`worker.routing.configuration{routing_fingerprint="<sha256>"} 1`. The
fingerprint covers `queues` and `groupQueueMappings`, not a worker process'
local `workerGroupId`. Compare the fingerprint across executor, scheduler, and
controller processes during and after a rollout; a mismatch means processes are
resolving `workerSelector.tags` and Worker Group subscriptions from different
tables.

## Static Routing Restart Order

The OSS routing table is static process configuration. Changes to `queues`,
`groupQueueMappings`, or a worker's `workerGroupId` are not hot-reloaded and do
not emit a `WORKER_GROUP_SYNC_REQUESTED` event. Operators must roll the affected
processes in an order that keeps executor and scheduler route decisions aligned
with the controller's subscriptions:

1. Roll controller processes with the updated `queues` and
   `groupQueueMappings`.
2. Roll affected workers so they reconnect to the updated controller and receive
   the expected queue subscriptions. Confirm each worker logs the expected
   resolved `workerGroupId`.
3. Confirm controller metrics show the expected worker group and worker queue
   labels for at least one connected worker serving each new queue, and that the
   controller routing fingerprint matches the new table.
4. Roll executor and scheduler processes with the same routing table so task and
   trigger `workerSelector.tags` start resolving only after subscribers exist.
   Confirm their routing fingerprints match the controller.

For worker-only changes to an already configured group, rolling the affected
workers is sufficient. When removing a queue, first stop or migrate flows and
triggers that route to it, then roll executor/scheduler so they stop emitting to
that queue, then remove controller subscriptions and worker group configuration.

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

## Static Limitation: Unserved Queue Deadlines

The OSS routing layer does not add a max-wait deadline or dead-letter transition
for a job after it has been emitted to a keyed Worker Job queue. This is an
explicit static-routing limitation.

Fallback is applied only at routing time, before enqueue, and only when the
configured metadata store cannot find a statically active queue among the
matched candidates:

- `FAIL` (also the default when `fallback` is omitted): task jobs fail before
  enqueue; trigger jobs are ignored with an error log and routing-failure
  metric.
- `CANCEL`: task jobs are cancelled before enqueue; trigger jobs are ignored
  with a warning log and routing-failure metric because there is no task run to
  cancel.
- `IGNORE`: the tag requirement is dropped and the job is emitted to the
  default Worker Job queue.
- `WAIT`: the job is emitted to the matched Worker Queue and waits in the
  durable keyed queue until a worker that subscribes to that queue connects.

Once a Worker Queue is statically reported active, fallback no longer runs. In
explicit `groupQueueMappings`, a queue is active when at least one configured
worker group subscribes to it; in compact mode, every configured queue is active
as a same-name candidate. If no connected worker currently serves that queue,
the task or trigger job is still emitted to the selected Worker Queue and waits
there unbounded by design. A matching worker connection creates the controller
subscriber and drains the queue; until then, backend queue lag plus
`controller.worker.active{worker_queue="<id>"} == 0` is the intended detection
signal.

A real deadline/dead-letter feature would need queue-contract work below this
static routing layer: message enqueue timestamps or deadlines, an atomic way to
move expired keyed messages to a terminal result or dead-letter destination, and
backend support across the keyed queue implementations. The current
`KeyedDispatchQueueInterface` exposes emit, subscribe, pause/resume, and lag
only. Creating controller subscribers solely to inspect unserved queues would
risk consuming and re-queuing jobs without a connected worker, creating the hot
loop this design avoids.

## Verification

Run the focused tests that cover routing resolution and worker connection
behavior:

```bash
./gradlew :core:test --tests "io.kestra.core.services.WorkerQueueServiceTest"
./gradlew :core:test --tests "io.kestra.core.worker.WorkerRoutingConfigurationTest"
./gradlew :core:test --tests "io.kestra.core.runners.ConfiguredWorkerQueueMetaStoreTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.ConfiguredWorkerQueueResolverTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.GrpcConnectControllerServiceTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.WorkerJobDispatcherTest"
./gradlew :scheduler:test --tests "io.kestra.scheduler.pubsub.TriggerWorkerJobPublisherTest"
```

For a live or integration environment, verify the following operational
invariants:

- workers log the expected resolved `workerGroup`;
- controller-side worker queue metrics include the expected worker group and
  worker queue labels;
- a task with `workerSelector.tags` lands on the expected worker host;
- a configured-but-unserved queue remains queued until a matching worker
  connects; compact-mode fallback does not apply after tags select a configured
  queue;
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
