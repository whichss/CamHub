import CamHubCore
import Foundation
import Network

@main
struct ControlConnectionSmoke {
    static func main() {
        guard CommandLine.arguments.count == 4,
              let port = UInt16(CommandLine.arguments[1]), let endpointPort = NWEndpoint.Port(rawValue: port) else {
            fatalError("Expected test port and certificate fingerprint")
        }
        let expectedFingerprint = CommandLine.arguments[2]
        let scenario = CommandLine.arguments[3]
        guard ["success", "reject-certificate", "wrong-pin", "invalid-format"].contains(scenario) else {
            fatalError("Unknown test scenario")
        }
        var verified = false
        var resolved = false
        let completionLock = NSLock()
        var completed = false
        let connection = CamHubHubControlConnection(
            endpoint: .hostPort(host: "127.0.0.1", port: endpointPort),
            pin: scenario == "invalid-format" ? "123" : (scenario == "wrong-pin" ? "87654321" : "12345678"),
            verify: { fingerprint, reply in
                verified = fingerprint == expectedFingerprint
                reply(verified && scenario != "reject-certificate")
            },
            onRemoteHost: { _ in resolved = true },
            onClockEstimate: { _ in },
            onMessage: { message in
                if scenario == "wrong-pin", message.type == "error",
                   message.stringValue == "invalid_pairing_pin" {
                    print("Incorrect PIN rejected")
                    connectionFinished.signal()
                    return
                }
                guard message.type == "hello_ack" else { return }
                guard scenario == "success", verified, resolved, message.deviceName == "Test Camera",
                      message.udpStreamPort == 12345, Data(base64Encoded: message.sessionKey)?.count == 32 else {
                    fputs("Invalid TLS handshake result\n", stderr); exit(1)
                }
                print("TLS fingerprint and pairing smoke test passed")
                completionLock.lock(); completed = true; completionLock.unlock()
                connectionFinished.signal()
            },
            onError: { error in
                completionLock.lock(); let alreadyCompleted = completed; completionLock.unlock()
                if scenario == "success", alreadyCompleted { return }
                if scenario == "reject-certificate", verified {
                    print("Unapproved certificate rejected")
                    connectionFinished.signal()
                } else if scenario == "invalid-format", error == "Enter the 8-digit camera PIN", !verified, !resolved {
                    print("Invalid PIN format rejected before connection")
                    connectionFinished.signal()
                } else {
                    fputs("Control error: \(error)\n", stderr); exit(1)
                }
            }
        )
        connection.start()
        DispatchQueue.global().async {
            if connectionFinished.wait(timeout: .now() + 40) != .success {
                fputs("Control handshake timed out\n", stderr); exit(1)
            }
            connection.stop()
            if scenario == "success" { Thread.sleep(forTimeInterval: 0.8) }
            exit(0)
        }
        dispatchMain()
    }
    private static let connectionFinished = DispatchSemaphore(value: 0)
}
