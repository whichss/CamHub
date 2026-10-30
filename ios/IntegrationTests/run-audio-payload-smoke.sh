#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
scratch_dir=$(mktemp -d "${TMPDIR:-/tmp}/camhub-audio-smoke.XXXXXX")
trap 'rm -rf "$scratch_dir"' EXIT HUP INT TERM

xcrun swiftc -module-cache-path "$scratch_dir/module-cache" \
    "$project_root/ios/CamHubCameraApp/CamHubAudioPayload.swift" \
    "$project_root/ios/IntegrationTests/AudioPayloadSmoke.swift" \
    -o "$scratch_dir/audio-payload-smoke"
"$scratch_dir/audio-payload-smoke"
