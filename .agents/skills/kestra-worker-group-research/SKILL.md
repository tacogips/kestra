---
name: kestra-worker-group-research
description: Investigate, explain, or troubleshoot this Kestra fork's tacogips-authored OSS worker group / worker queue routing feature. Use when Codex is asked about Kestra worker groups, worker-side workerGroupId settings, workerSelector.tags routing, ConfiguredWorkerQueueService, ConfiguredWorkerQueueResolver, WorkerJobDispatcher routing indices, or the tacogips commits that added static OSS worker routing to main.
---

# Kestra Worker Group Research

Use this skill inside the `tacogips/kestra` checkout when answering questions or making changes around the OSS worker group routing feature.

## Required Workflow

1. Start from source, not memory.
   - Confirm the checkout with `git branch --show-current` and `git status --short`.
   - Use `rg` first for source discovery.
   - Read the repository `AGENTS.md`; read `ui/AGENTS.md` only for UI changes.

2. Identify the relevant tacogips commits when the question is historical.
   - Primary feature commit: `f64927b52 feat(worker): add static OSS worker routing`.
   - Important follow-up commits: `7cc6925f9`, `b1661a49b`, `527a2078f`, `98b507806`, `8b621158c`, `3d6563d3a`.
   - Use:
     ```bash
     git log --all --author='tacogips' --grep='worker\|routing\|group\|queue' --regexp-ignore-case --date=short --pretty=format:'%h %ad %an <%ae> %s'
     git show --stat --oneline f64927b52
     ```

3. Load `references/worker-group-map.md` before explaining behavior, changing code, or debugging routing. It contains the implementation map, configuration model, matching rules, and common wrong assumptions.

4. For a quick fresh index of the current checkout, run:
   ```bash
   .agents/skills/kestra-worker-group-research/scripts/collect-worker-group-context.sh
   ```

5. When explaining the feature, distinguish these concepts clearly:
   - `workerGroupId`: the configured group identity a worker advertises when connecting.
   - Worker Group: a configured subscription group for one or more Worker Queues.
   - Worker Queue: the dispatch key selected by `workerSelector.tags`.
   - `workerSelector.tags`: the flow/task/trigger routing request, not the worker's identity.

## Source Anchors

Prefer these files as entry points:

- `docs/architecture/OSS_WORKER_ROUTING.md`
- `README.md` section "OSS Worker Group Routing"
- `core/src/main/java/io/kestra/core/worker/WorkerRoutingConfiguration.java`
- `core/src/main/java/io/kestra/core/worker/WorkerGroups.java`
- `core/src/main/java/io/kestra/core/services/ConfiguredWorkerQueueService.java`
- `core/src/main/java/io/kestra/core/runners/ConfiguredWorkerQueueMetaStore.java`
- `worker/src/main/java/io/kestra/worker/services/GrpcWorkerConnectionService.java`
- `worker/src/main/java/io/kestra/worker/WorkerAgent.java`
- `worker-controller/src/main/java/io/kestra/controller/grpc/services/GrpcConnectControllerService.java`
- `worker-controller/src/main/java/io/kestra/controller/grpc/services/ConfiguredWorkerQueueResolver.java`
- `worker-controller/src/main/java/io/kestra/controller/grpc/services/WorkerJobDispatcher.java`

## Verification

For behavior changes, run focused tests before broader builds:

```bash
./gradlew :core:test --tests "io.kestra.core.services.WorkerQueueServiceTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.ConfiguredWorkerQueueResolverTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.GrpcConnectControllerServiceTest"
./gradlew :worker-controller:test --tests "io.kestra.controller.grpc.services.WorkerJobDispatcherTest"
```

If executor dispatch behavior is changed, follow repository guidance and consider executor tests, including `H2RunnerTest` where applicable.
