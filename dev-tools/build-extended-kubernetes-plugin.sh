#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${1:-${repository_root}/plugins}"
source_commit="882d7794d811314b312f5325a9a393732ddbba4e"
plugin_version="1.12.1-security.20261023"
build_directory="$(mktemp -d)"
trap 'rm -rf -- "$build_directory"' EXIT

git -C "$build_directory" init --quiet
git -C "$build_directory" remote add origin https://github.com/kestra-io/plugin-kubernetes.git
git -C "$build_directory" fetch --depth 1 origin "$source_commit"
git -C "$build_directory" checkout --detach FETCH_HEAD

(
    cd "$build_directory"
    ./gradlew --init-script "${repository_root}/dev-tools/extended-plugin-security.init.gradle" \
        -Pversion="$plugin_version" shadowJar test \
        --tests io.kestra.plugin.kubernetes.core.PodCreateResumeSelectionTest
)

mkdir -p "$destination"
install -m 644 "${build_directory}/build/libs/plugin-kubernetes-${plugin_version}.jar" \
    "${destination}/io_kestra_plugin__plugin-kubernetes__1_12_1_security_20261023.jar"
