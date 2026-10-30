#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
scratch_dir=$(mktemp -d "${TMPDIR:-/tmp}/camhub-tls-smoke.XXXXXX")
server_pid=
cleanup() {
    if [ -n "$server_pid" ]; then
        kill "$server_pid" 2>/dev/null || :
        wait "$server_pid" 2>/dev/null || :
    fi
    rm -rf "$scratch_dir"
}
trap cleanup EXIT HUP INT TERM

openssl req -x509 -newkey rsa:2048 -sha256 -nodes -subj '/CN=CamHubTest' \
    -days 1 -keyout "$scratch_dir/key.pem" -out "$scratch_dir/cert.pem" >/dev/null 2>&1
fingerprint=$(openssl x509 -in "$scratch_dir/cert.pem" -outform DER | openssl dgst -sha256 | awk '{print toupper($NF)}')

xcrun swiftc -module-cache-path "$scratch_dir/cache" \
    -emit-library -emit-module -module-name CamHubCore \
    -emit-module-path "$scratch_dir/CamHubCore.swiftmodule" \
    -o "$scratch_dir/libCamHubCore.dylib" \
    "$project_root/ios/CamHubCore/Sources/CamHubCore/CamHubWireProtocol.swift"
xcrun swiftc -module-cache-path "$scratch_dir/cache" -I "$scratch_dir" -L "$scratch_dir" \
    -lCamHubCore -Xlinker -rpath -Xlinker "$scratch_dir" \
    "$project_root/ios/CamHubCameraApp/CamHubHubControlConnection.swift" \
    "$project_root/ios/IntegrationTests/ControlConnectionSmoke.swift" \
    -o "$scratch_dir/control-smoke"

for scenario in success reject-certificate wrong-pin; do
    rm -f "$scratch_dir/port"
    python3 "$project_root/ios/IntegrationTests/control_test_server.py" \
        "$scratch_dir/cert.pem" "$scratch_dir/key.pem" "$scratch_dir/port" "$scenario" &
    server_pid=$!
    attempt=0
    while [ ! -s "$scratch_dir/port" ]; do
        kill -0 "$server_pid" 2>/dev/null || { wait "$server_pid"; exit 1; }
        attempt=$((attempt + 1))
        [ "$attempt" -lt 50 ] || { echo 'Test server did not start' >&2; exit 1; }
        sleep 0.1
    done
    "$scratch_dir/control-smoke" "$(cat "$scratch_dir/port")" "$fingerprint" "$scenario"
    wait "$server_pid"
    server_pid=
done
"$scratch_dir/control-smoke" 1 "$fingerprint" invalid-format
