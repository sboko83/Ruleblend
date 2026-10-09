// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "translate-helper",
    // 26.4 is where TranslationSession's public init and the lowLatency/highFidelity
    // strategies landed; earlier releases only expose the session through SwiftUI.
    platforms: [.macOS("26.4")],
    targets: [
        .executableTarget(name: "rb-translate", path: "Sources/rb-translate")
    ]
)
