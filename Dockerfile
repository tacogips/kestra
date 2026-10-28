ARG BASE_IMAGE="ghcr.io/kestra-io/kestra-base:latest-slim"
FROM ${BASE_IMAGE}

USER root

COPY dev-tools/distrust-emudhra.sh /tmp/distrust-emudhra.sh
RUN apt-get update && \
    apt-get upgrade -y && \
    sh /tmp/distrust-emudhra.sh && \
    rm -f /tmp/distrust-emudhra.sh && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

ENV PATH="/app/.venv/bin:$PATH"

COPY --chown=kestra:kestra docker /

RUN --mount=type=bind,target=/mnt/context \
    mkdir -p /app/plugins && \
    { cp -r /mnt/context/plugins/. /app/plugins/ 2>/dev/null || true; } && \
    chown -R kestra:kestra /app

USER kestra

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["--help"]
