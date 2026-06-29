---
name: kestra-ghcr-image-publish
description: Build, publish, verify, or troubleshoot this Kestra fork's OSS worker routing container image in GitHub Container Registry. Use when Codex is asked to push the Kestra runtime image, update container publishing, validate multi-architecture images for Apple Silicon or Linux AMD64, inspect GHCR tags, or change `.github/workflows/oss-worker-routing-image.yml`; always prefer GHCR and never Docker Hub for this image.
---

# Kestra GHCR Image Publish

## Operating Rules

- Publish this fork's OSS worker routing image to `ghcr.io/tacogips/kestra`, not Docker Hub.
- Treat `ghcr.io/tacogips/kestra:oss-worker-routing` as the stable tag for the branch image.
- Keep the commit-specific tag `ghcr.io/tacogips/kestra:oss-worker-routing-<commit-sha>` when changing the workflow.
- Preserve multi-architecture support for `linux/amd64` and `linux/arm64`.
- Do not commit generated image inputs such as `docker/app/kestra`; it is a build artifact.
- Do not print, store, or commit registry tokens. Prefer GitHub Actions with `secrets.GITHUB_TOKEN` over local credential handling.

## Preferred Publish Path

Use the GitHub Actions workflow unless the user explicitly requests a local push:

```bash
gh workflow run oss-worker-routing-image.yml --ref feature/oss-worker-routing
gh run list --branch feature/oss-worker-routing --workflow oss-worker-routing-image.yml --limit 3
gh run watch <run-id> --exit-status
```

The workflow file is `.github/workflows/oss-worker-routing-image.yml`. It should:

- run on branch `feature/oss-worker-routing` and `workflow_dispatch`;
- use `permissions: contents: read` and `packages: write`;
- log in to `ghcr.io`;
- build `./gradlew writeExecutableJar --no-daemon`;
- copy `build/executable/kestra-2.0.0-SNAPSHOT` to `docker/app/kestra`;
- publish a manifest list for `linux/amd64,linux/arm64`;
- push only `ghcr.io/${{ github.repository }}:*` tags.

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
docker build --no-cache -t ghcr.io/tacogips/kestra:oss-worker-routing .
docker run --rm ghcr.io/tacogips/kestra:oss-worker-routing --version
```

On this Mac, Colima may require setting `DOCKER_HOST` to the Colima socket before invoking Docker. Prefer the current Docker context when it already works.

For a local multi-architecture validation without pushing:

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --provenance=false \
  -t ghcr.io/tacogips/kestra:oss-worker-routing \
  --output type=oci,dest=/tmp/kestra-oss-worker-routing-multiarch.oci \
  .
```

## Registry Verification

After the workflow finishes, verify the registry image:

```bash
docker buildx imagetools inspect ghcr.io/tacogips/kestra:oss-worker-routing
```

Confirm the output includes both:

- `linux/amd64`
- `linux/arm64`

When reporting success, include the GHCR image tag, digest if available, and the GitHub Actions run URL.

## Failure Handling

- If a local push fails because credentials lack `packages: write`, switch back to the GitHub Actions workflow instead of storing a broader token locally.
- If GHCR package visibility or permissions block pulls, inspect the package settings in GitHub rather than adding Docker Hub fallback tags.
- If a user asks for Docker Hub publishing, challenge it and explain that this repository's accepted publishing target is GHCR unless they explicitly override the architecture decision.
