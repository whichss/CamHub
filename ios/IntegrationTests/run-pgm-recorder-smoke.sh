#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
export CAMHUB_TEST_FIXTURES="$project_root/ios/IntegrationTests/Fixtures"
scratch_dir=$(mktemp -d "${TMPDIR:-/tmp}/camhub-pgm-smoke.XXXXXX")
trap 'rm -rf "$scratch_dir"' EXIT HUP INT TERM

xcrun swiftc -module-cache-path "$scratch_dir/cache" \
    -emit-library -emit-module -module-name CamHubCore \
    -emit-module-path "$scratch_dir/CamHubCore.swiftmodule" \
    -o "$scratch_dir/libCamHubCore.dylib" \
    "$project_root/ios/CamHubCore/Sources/CamHubCore/CamHubWireProtocol.swift"
xcrun swiftc -module-cache-path "$scratch_dir/cache" -I "$scratch_dir" -L "$scratch_dir" \
    -lCamHubCore -Xlinker -rpath -Xlinker "$scratch_dir" \
    -framework AVFoundation -framework AudioToolbox -framework CoreImage -framework VideoToolbox \
    "$project_root/ios/CamHubCameraApp/CamHubAudioPayload.swift" \
    "$project_root/ios/CamHubCameraApp/CamHubHubPgmRecorder.swift" \
    "$project_root/ios/IntegrationTests/PGMRecorderSmoke.swift" \
    -o "$scratch_dir/pgm-recorder-smoke"
"$scratch_dir/pgm-recorder-smoke"
