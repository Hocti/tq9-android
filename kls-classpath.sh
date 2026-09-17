#!/usr/bin/env bash
# fwcd Kotlin Language Server extra classpath (colon-separated).
# AGP's resolver only dumps the main variant compileClasspath, so
# testImplementation jars such as JUnit never reach the IDE.
set -euo pipefail

caches=(
    "${GRADLE_USER_HOME:-${HOME}/.gradle}/caches/modules-2/files-2.1"
    "${HOME}/.gradle/caches/modules-2/files-2.1"
)

find_jar() {
    local group="$1" artifact="$2" version="$3" cache dir jar
    for cache in "${caches[@]}"; do
        dir="${cache}/${group}/${artifact}/${version}"
        [[ -d "$dir" ]] || continue
        jar="$(find "$dir" -name "${artifact}-${version}.jar" \
            ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
            -print -quit 2>/dev/null || true)"
        if [[ -n "${jar}" ]]; then
            printf '%s\n' "${jar}"
            return 0
        fi
    done
    return 0
}

paths=()
add_jar() {
    local jar=""
    jar="$(find_jar "$1" "$2" "$3")"
    if [[ -n "${jar}" ]]; then
        paths+=("${jar}")
    fi
}

# Keep in sync with app/build.gradle.kts testImplementation("junit:junit:…")
add_jar junit junit 4.13.2
add_jar org.hamcrest hamcrest-core 1.3

if (( ${#paths[@]} > 0 )); then
    (
        IFS=:
        printf '%s\n' "${paths[*]}"
    )
fi
