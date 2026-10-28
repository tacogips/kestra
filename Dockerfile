ARG BASE_IMAGE="ghcr.io/kestra-io/kestra-base:latest-slim"
FROM ${BASE_IMAGE} AS security-updates

USER root

COPY dev-tools/distrust-emudhra.sh /tmp/distrust-emudhra.sh
COPY dev-tools/verify-brotli.sh /tmp/verify-brotli.sh
RUN apt-get update && \
    apt-get upgrade -y && \
    apt-get install -y --no-install-recommends libbrotli1 && \
    sh /tmp/verify-brotli.sh && \
    sh /tmp/distrust-emudhra.sh && \
    rm -f /tmp/distrust-emudhra.sh /tmp/verify-brotli.sh && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

ENV PATH="/app/.venv/bin:$PATH"

USER kestra

FROM security-updates

USER root

COPY --chown=kestra:kestra docker /

RUN --mount=type=bind,target=/mnt/context \
    mkdir -p /app/plugins && \
    { cp -r /mnt/context/plugins/. /app/plugins/ 2>/dev/null || true; } && \
    chown -R kestra:kestra /app

USER kestra

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["--help"]
