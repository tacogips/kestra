# Extended image security maintenance

The Docker Hub image `tacogips/kestra-extended:oss-worker-routing` retains the OSS
worker routing implementation and the plugins pinned in
`dev-tools/extended-plugins.txt`, plus the locally rebuilt Kubernetes plugin.
The upstream base is **v2.0.7**, commit
`49b62a2c820d37e98a1c7b63aa35fd83f960d4ec`; the fork is a separate commit on top.
Docker Hub is the explicitly requested target
for this image; the playground's Artifact Registry deployment remains separate.

## October 2026 remediation

- Remove the eMudhra incident's trusted roots from the Ubuntu and Java trust
  stores, including the Sectigo cross-signing path. Java aliases differ by
  vendor, so the removal script matches root certificate SHA-256 fingerprints.
- Rebuild on Ubuntu 24.04 with upgraded OS packages and uv 0.13.5; Python variants
  explicitly require certifi 2026.10.19 or later.
- Align Jackson at 2.22.4 and Netty at 4.2.19.Final without changing the release's
  Micronaut major version.
- Rebuild Kubernetes plugin 1.12.1 from pinned upstream source commit
  `882d7794d811314b312f5325a9a393732ddbba4e` as `1.12.1-security.20261023`, with
  Jackson 2.22.4. Even the published 1.12.1 embeds vulnerable Jackson copies that
  updating the application BOM alone does not replace.
- Refresh UI dependencies, including Axios, Vue, Storybook, Vitest, DOMPurify,
  Vite and transitive dependencies. Keep the SDK generator's YAML parser on
  patched js-yaml 4.3.2 or later.
- Override Mermaid's KaTeX dependency with the fixed 0.18.2 release for
  CVE-2026-103923, preserving the existing Mermaid major version.
- Apply a local `braces` patch for CVE-2026-93687, which has no released upstream
  fix. Limit parser and AST walker nesting to 100 and cover string, external AST,
  normal expansion and escaped-input cases with regression tests.

Root removal can affect legitimate services chaining to those roots. Replace
affected service certificates. Custom trust stores and certificates mounted at
deployment time require the same review; image changes cannot update them.

References:

- [Oracle's current Java mitigation](https://ops.java/emudhra)
- [Incident details and public test chains](https://emudhra.we-have-a-problem.com/)
- [Sonatype's fingerprint blocklist bypass verification](https://www.sonatype.com/blog/the-limits-of-fingerprint-blocklists-a-reproducible-java-certificate-bypass)

## Rebuild

Run from the repository root with Java 25, npm and Docker Buildx available.
Use a clean plugin directory so older plugin versions cannot remain in the image.

```bash
(cd ui && npm ci && npm run build)
./gradlew writeExecutableJar
cp build/executable/kestra-2.0.7 docker/app/kestra
chmod 755 docker/app/kestra
while IFS= read -r plugin; do
    docker/app/kestra plugins install "$plugin" --plugins plugins
done < dev-tools/extended-plugins.txt
dev-tools/build-extended-kubernetes-plugin.sh plugins
docker buildx build --pull --platform linux/amd64,linux/arm64 \
    --provenance=false --load -f Dockerfile.base -t kestra-security-base:local .
docker buildx build --platform linux/amd64,linux/arm64 \
    --build-arg BASE_IMAGE=kestra-security-base:local --provenance=false --load \
    -t tacogips/kestra-extended:security-candidate .
```

Verify both architectures with Trivy before updating the stable tag. Preserve an
immutable source-commit tag and the resulting manifest digest for rollback.

## Trust regression check

Compile `dev-tools/tests/VerifyEmudhraTrust.java` with Java 25 and mount its output
directory into the image. Run it with `java -cp /tests VerifyEmudhraTrust` to check
that the incident roots are absent and unrelated public roots remain. An optional
directory argument checks that every PEM chain in that directory is rejected.
The public incident archive linked above contains fourteen such chains, including
the direct, cross-signed and non-CT paths. Also validate the system bundle with
OpenSSL against the same chains.

For Node 26 tests, disable native Web Storage so jsdom provides its own storage:
`NODE_OPTIONS='--max-old-space-size=4096 --no-experimental-webstorage' npm run test:unit`.

The npm advisory service still reports `braces` and its dependents because
`patch-package` preserves the upstream package version. The checked-in patch
mitigates that advisory locally; keep the regression test and verify the patch
applies after `npm ci`. Do not interpret these version-based alerts as a clean
registry audit; verify the local regression tests as well as the image scan.
