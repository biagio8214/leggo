import SwiftUI
import shared

@main
struct iosApp: App {
    init() {
        // Inizializzazione del database e dei manager
        PlatformUtils_iosKt.doInit()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
