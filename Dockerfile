ARG BASE_IMAGE="ghcr.io/kestra-io/kestra-base:latest-no-plugins"
ARG KESTRA_EXTRA_PLUGINS="io.kestra.storage:storage-gcs:1.2.0"
FROM ${BASE_IMAGE}
ARG KESTRA_EXTRA_PLUGINS

ENV PATH="/app/.venv/bin:$PATH"

COPY --chown=kestra:kestra docker /

RUN --mount=type=bind,target=/mnt/context \
    mkdir -p /app/plugins && \
    { cp -r /mnt/context/plugins/. /app/plugins/ 2>/dev/null || true; } && \
    chown -R kestra:kestra /app

RUN set -eux; \
    for plugin in ${KESTRA_EXTRA_PLUGINS}; do \
      /app/kestra plugins install "${plugin}" \
        --plugins /app/plugins \
        --repositories=https://central.sonatype.com/repository/maven-snapshots; \
    done; \
    chown -R kestra:kestra /app/plugins

USER kestra

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["--help"]
