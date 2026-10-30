#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
scratch_dir=$(mktemp -d "${TMPDIR:-/tmp}/camhub-udp-smoke.XXXXXX")
trap 'rm -rf "$scratch_dir"' EXIT HUP INT TERM

xcrun swiftc -module-cache-path "$scratch_dir/cache" \
    -emit-library -emit-module -module-name CamHubCore \
    -emit-module-path "$scratch_dir/CamHubCore.swiftmodule" \
    -o "$scratch_dir/libCamHubCore.dylib" \
    "$project_root/ios/CamHubCore/Sources/CamHubCore/CamHubWireProtocol.swift"
xcrun swiftc -module-cache-path "$scratch_dir/cache" -I "$scratch_dir" -L "$scratch_dir" \
    -lCamHubCore -Xlinker -rpath -Xlinker "$scratch_dir" \
    "$project_root/ios/CamHubCameraApp/CamHubUdpVideoReceiver.swift" \
    "$project_root/ios/IntegrationTests/UDPReceiverSmoke.swift" \
    -o "$scratch_dir/udp-receiver-smoke"
"$scratch_dir/udp-receiver-smoke"
