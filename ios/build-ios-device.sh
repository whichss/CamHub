#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
configuration=${1:-Debug}

case "$configuration" in
    Debug|Release) ;;
    *) echo "Usage: $0 [Debug|Release]" >&2; exit 2 ;;
esac

# Xcode builds the local Swift package in its own directory. Pass that module directory to the
# app target explicitly so the package import is available during a direct device-target build.
package_products="$project_root/CamHubCore/build/$configuration-iphoneos"

exec xcodebuild \
    -project "$project_root/CamHubCameraApp.xcodeproj" \
    -target CamHubCamera \
    -configuration "$configuration" \
    -sdk iphoneos \
    CODE_SIGNING_ALLOWED=NO \
    SWIFT_INCLUDE_PATHS="$package_products" \
    build
