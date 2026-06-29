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

## Configuration

Controller-side services need the full routing table so the executor can map
`workerSelector.tags` to queue ids and the worker controller can map group ids to
queue subscriptions:

```yaml
kestra:
  worker:
    routing:
      groups:
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

For a compact deployment, an explicit group block is optional. If a worker
requests a group id that has the same name as a configured queue id, the
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
      groups:
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

## Multi-Architecture Image

The runtime executable is architecture-independent Java bytecode, but the base
image must match the host CPU. Build and push a manifest list with both supported
platforms:

```bash
./gradlew writeExecutableJar
cp build/executable/kestra-2.0.0-SNAPSHOT docker/app/kestra
chmod 755 docker/app/kestra

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --provenance=false \
  -t tacogips/kestra:oss-worker-routing \
  --push \
  .
```

Use `linux/arm64` for Apple Silicon local containers and `linux/amd64` for the
default GKE node pool architecture. For local validation without registry push:

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --provenance=false \
  -t tacogips/kestra:oss-worker-routing \
  --output type=oci,dest=/tmp/kestra-oss-worker-routing-multiarch.oci \
  .
```
