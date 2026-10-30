import Combine
import Foundation
import Network

struct CamHubDiscoveredCamera: Identifiable {
    let id: String
    let name: String
    let endpoint: NWEndpoint
}

/// Bonjour discovery is separate from the camera sender and publishes UI state on the main queue.
final class CamHubHubDiscovery: ObservableObject {
    @Published private(set) var cameras: [CamHubDiscoveredCamera] = []
    @Published private(set) var error: String?
    private let queue = DispatchQueue(label: "com.camhub.hub.discovery")
    private var browser: NWBrowser?
    private let generationLock = NSLock()
    private var generation: UInt64 = 0

    private func nextGeneration() -> UInt64 {
        generationLock.lock(); defer { generationLock.unlock() }
        generation &+= 1
        return generation
    }

    private func isCurrent(_ token: UInt64) -> Bool {
        generationLock.lock(); defer { generationLock.unlock() }
        return generation == token
    }

    func start() {
        queue.async {
            guard self.browser == nil else { return }
            let token = self.nextGeneration()
            DispatchQueue.main.async {
                guard self.isCurrent(token) else { return }
                self.cameras = []
                self.error = nil
            }
            let browser = NWBrowser(for: .bonjour(type: "_camhub._tcp", domain: nil), using: .tcp)
            self.browser = browser
            browser.stateUpdateHandler = { [weak self, weak browser] state in
                guard let self, let browser, self.browser === browser else { return }
                if case let .failed(failure) = state {
                    self.browser = nil
                    browser.cancel()
                    DispatchQueue.main.async {
                        guard self.isCurrent(token) else { return }
                        self.cameras = []
                        self.error = failure.localizedDescription
                    }
                }
            }
            browser.browseResultsChangedHandler = { [weak self, weak browser] results, _ in
                guard let self, let browser, self.browser === browser else { return }
                var unique: [String: CamHubDiscoveredCamera] = [:]
                for result in results {
                    guard case let .service(name, type, domain, _) = result.endpoint else { continue }
                    let id = "\(name)|\(type)|\(domain)"
                    let display = name.hasPrefix("CamHub_") ? String(name.dropFirst(7)) : name
                    if unique[id] == nil {
                        unique[id] = CamHubDiscoveredCamera(
                            id: id, name: display,
                            endpoint: .service(name: name, type: type, domain: domain, interface: nil)
                        )
                    }
                }
                let cameras = unique.values.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
                DispatchQueue.main.async {
                    guard self.isCurrent(token) else { return }
                    self.cameras = cameras
                    self.error = nil
                }
            }
            browser.start(queue: self.queue)
        }
    }

    func stop() {
        queue.async {
            let token = self.nextGeneration()
            self.browser?.cancel()
            self.browser = nil
            DispatchQueue.main.async {
                guard self.isCurrent(token) else { return }
                self.cameras = []
                self.error = nil
            }
        }
    }
}
