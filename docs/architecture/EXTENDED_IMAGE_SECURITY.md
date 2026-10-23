# Extended image security maintenance

The Docker Hub image `tacogips/kestra-extended:oss-worker-routing` retains the OSS
worker routing implementation and the plugins pinned in
`dev-tools/extended-plugins.txt`. Docker Hub is the explicitly requested target
for this image; the playground's Artifact Registry deployment remains separate.

## October 2026 remediation

- Remove the eMudhra incident's trusted roots from the Ubuntu and Java trust
  stores, including the Sectigo cross-signing path. Java aliases differ by
  vendor, so the removal script matches root certificate SHA-256 fingerprints.
- Rebuild on Ubuntu 24.04 with upgraded OS packages and uv 0.13.5.
- Align Jackson at 2.22.3 and Netty at 4.2.17.Final.
- Update the Kubernetes plugin from 1.9.5 to 1.12.1: the older plugin embeds
  vulnerable Jackson and Netty copies that updating the application BOM alone
  does not replace.
- Refresh UI dependencies, including Axios, Vue, Storybook, Vitest, DOMPurify,
  Vite and transitive dependencies. Keep the SDK generator's YAML parser on
  patched js-yaml 4.3.2 or later.

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
cp build/executable/kestra-2.0.0-SNAPSHOT docker/app/kestra
chmod 755 docker/app/kestra
while IFS= read -r plugin; do
    docker/app/kestra plugins install "$plugin" --plugins plugins
done < dev-tools/extended-plugins.txt
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

The npm audit still reports unpatched `braces` through the repository-controlled
`patch-package` installation path, plus a low-severity KaTeX advisory through
Mermaid. Do not use untrusted patch files. The patch tool is used during the
build; the KaTeX issue remains a low-severity UI dependency risk. Track their
upstream fixes separately instead of forcing an older
patch tool or an unrelated Mermaid major upgrade.
