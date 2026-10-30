# CamHub iOS implementation

`CamHubCore` is the iOS interoperability library. It mirrors the current Android/desktop video
payload v2-v4 decoder, v4 encoder, CamHub RTP-style packetization and AES-256-GCM frame envelope.
The sibling `CamHubCameraApp.xcodeproj` contains a SwiftUI camera UI and the initial capture,
pairing, Bonjour discovery, TLS control and UDP/RTP sender paths.

## Compatibility contract

- Payload envelope: big-endian v4 metadata followed by one H.264 access unit.
- RTP-style datagrams: 32-byte CamHub header, dynamic payload type 96 and maximum 1,200 bytes.
- Frame encryption: 12-byte nonce followed by AES-GCM ciphertext and 16-byte tag.
- Pairing and connection control must follow `PeerConnectionManager.kt` and `TlsHelper.kt`;
  do not invent a separate JSON schema or treat the display-name/PIN exchange as TLS identity.

## Remaining implementation slices

1. Cross-language golden fixtures for payload v4, RTP packetization and AES-GCM known-answer
   decryption are shared with the Kotlin `protocol` tests.
2. IPv4-mapped and native IPv6 UDP destinations are implemented, including link-local scope.
   The iOS app target includes an authenticated, AES-GCM protected TCP video fallback; SRT is not
   implemented by the iOS source.
3. The iOS app target now builds in GitHub Actions for arm64. Validate sender compatibility with
   Android and desktop receivers on devices; the shared vectors verify serialization, not
   end-to-end network interoperability.

`swift test --package-path ios/CamHubCore` builds the Swift core and passes the shared vectors. The
arm64 device Debug/Release targets and an unsigned iOS Simulator Debug target also build in CI;
the app is not launched. Local simulator execution is unavailable because the installed
CoreSimulator components do not match the local Xcode version. The app has not been
launched, and device-to-device compatibility remains unverified.
