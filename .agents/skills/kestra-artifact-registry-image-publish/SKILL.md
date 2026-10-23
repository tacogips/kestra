---
name: kestra-artifact-registry-image-publish
description: Build, publish, verify, or troubleshoot this Kestra fork's OSS worker routing container image in Google Artifact Registry through the kestra-playground deployment workflow. Use when Codex is asked to push the Kestra runtime image, update container publishing, validate multi-architecture images for Apple Silicon or Linux AMD64, inspect Artifact Registry tags, or change the routed image publication flow; always prefer Google Artifact Registry and never Docker Hub or GHCR for this image.
---

# Kestra Artifact Registry Image Publish

## Operating Rules

- Publish this fork's OSS worker routing image to Google Artifact Registry under
  `kestra-playground/kestra-oss-worker-routing`, not Docker Hub or GHCR.
- Treat
  `<region>-docker.pkg.dev/<project-id>/kestra-playground/kestra-oss-worker-routing:oss-worker-routing`
  as the stable tag for the branch image.
- Keep commit-specific tags for both the playground deployment commit and the Kestra source commit
  when changing the workflow.
- Preserve multi-architecture support for `linux/amd64` and `linux/arm64`.
- Do not commit generated image inputs such as `docker/app/kestra`; it is a build artifact.
- Do not print, store, or commit registry tokens. Prefer the `kestra-playground` GitHub Actions
  Workload Identity Federation path over local credential handling.

## Preferred Publish Path

Use the `kestra-playground` GitHub Actions routed deployment workflow. It checks out this fork,
builds the routed Kestra executable, installs the GCS storage plugin, pushes the image to Artifact
Registry, and deploys the SHA-tagged image:

```bash
cd <kestra-playground-checkout>
gh workflow run deploy.yml --ref main -f target_environment=routed -f run_batch=false
gh run list --branch main --workflow deploy.yml --limit 3
gh run watch <run-id> --exit-status
```

The workflow file is `kestra-playground/.github/workflows/deploy.yml`. Its routed image job should:

- run only for `workflow_dispatch` with `target_environment=routed`;
- use `permissions: contents: read` and `id-token: write`;
- authenticate to Google Cloud with Workload Identity Federation;
- configure Docker auth for `asia-northeast1-docker.pkg.dev`;
- check out `tacogips/kestra@feature/oss-worker-routing`;
- build `./gradlew writeExecutableJar --no-daemon`;
- copy `build/executable/kestra-2.0.0-SNAPSHOT` to `docker/app/kestra`;
- install `io.kestra.storage:storage-gcs`;
- publish a manifest list for `linux/amd64,linux/arm64`;
- push only Artifact Registry tags under `kestra-playground/kestra-oss-worker-routing`.

If editing the workflow, also use the `secure-github-action` skill. Keep actions pinned to full commit SHAs and keep `persist-credentials: false` on checkout.

## Local Build Checks

Before changing the workflow or reporting the image is ready, verify the executable can be built:

```bash
nix develop --command ./gradlew writeExecutableJar --no-daemon
cp build/executable/kestra-2.0.0-SNAPSHOT docker/app/kestra
chmod 755 docker/app/kestra
```

For a local single-platform smoke test, build and run the image with the active Docker context:

```bash
docker build --no-cache -t kestra-oss-worker-routing:local .
docker run --rm kestra-oss-worker-routing:local --version
```

On this Mac, Colima may require setting `DOCKER_HOST` to the Colima socket before invoking Docker. Prefer the current Docker context when it already works.

For a local multi-architecture validation without pushing:

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --provenance=false \
  -t kestra-oss-worker-routing:local \
  --output type=oci,dest=/tmp/kestra-oss-worker-routing-multiarch.oci \
  .
```

## Registry Verification

After the workflow finishes, verify the registry image:

```bash
docker buildx imagetools inspect "${REGION}-docker.pkg.dev/${PROJECT_ID}/kestra-playground/kestra-oss-worker-routing:oss-worker-routing"
```

Confirm the output includes both:

- `linux/amd64`
- `linux/arm64`

When reporting success, include the Artifact Registry image tag, digest if available, and the GitHub
Actions run URL.

## Failure Handling

- If a local push fails because credentials lack Artifact Registry write permission, switch back to
  the `kestra-playground` GitHub Actions workflow instead of storing broader credentials locally.
- If Artifact Registry pulls fail, inspect Artifact Registry IAM and image tags rather than adding
  Docker Hub or GHCR fallback tags.
- If a user asks for Docker Hub or GHCR publishing, challenge it and explain that this repository's
  accepted publishing target is Google Artifact Registry unless they explicitly override the
  architecture decision.
