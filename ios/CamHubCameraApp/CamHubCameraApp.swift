import SwiftUI

@main
struct CamHubCameraApp: App {
    var body: some Scene {
        WindowGroup {
            NavigationStack {
                VStack(spacing: 20) {
                    Text("CamHub").font(.largeTitle.bold())
                    NavigationLink("Camera mode") { CamHubCameraScreen() }
                    NavigationLink("Hub mode") { CamHubHubScreen() }
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }
}
