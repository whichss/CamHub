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
   Reliable TCP/SRT fallback is still pending.
3. Build the iOS app target and validate sender compatibility with Android and desktop receivers;
   the shared vectors verify serialization, not end-to-end network interoperability.

`swift test --package-path ios/CamHubCore` builds the Swift core and passes the shared vectors. The
app sources have been type-checked and linked for arm64 iOS 16, but the Xcode app target build is
still unverified because the installed iOS platform and CoreSimulator components do not match the
local Xcode version. The app has not been launched, and device-to-device compatibility remains
unverified.
