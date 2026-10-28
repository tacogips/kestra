#!/usr/bin/env sh
set -eu

. /etc/os-release

# Distribution security updates backport CVE-2026-111497 without changing the upstream version.
case "$ID:$VERSION_CODENAME" in
    ubuntu:jammy) fixed_version=1.0.9-2ubuntu0.1 ;;
    ubuntu:noble) fixed_version=1.1.0-2ubuntu0.1 ;;
    ubuntu:resolute) fixed_version=1.2.0-3ubuntu0.1 ;;
    ubuntu:stonking) fixed_version=1.2.0-4ubuntu0.1 ;;
    debian:bookworm) fixed_version=1.0.9-2+deb12u1 ;;
    debian:trixie) fixed_version=1.1.0-2+deb13u1 ;;
    *)
        printf 'Cannot verify the Brotli security update for %s:%s.\n' "$ID" "$VERSION_CODENAME" >&2
        exit 1
        ;;
esac

installed_version=$(dpkg-query -W -f='${Version}' libbrotli1)
if ! dpkg --compare-versions "$installed_version" ge "$fixed_version"; then
    printf 'libbrotli1 %s is vulnerable to CVE-2026-111497; install %s or later.\n' "$installed_version" "$fixed_version" >&2
    exit 1
fi
