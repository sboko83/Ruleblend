import CoreGraphics
import Foundation
import ImageIO

// Removes the opaque black canvas around the icon's existing rounded silhouette, pads it to the
// standard macOS icon margin (artwork 824 of 1024), then rebuilds the macOS icon family and the
// margin-free rail logo. The scan follows the artwork itself instead of imposing another radius.
let arguments = CommandLine.arguments
guard arguments.count == 3 || arguments.count == 4 else {
    FileHandle.standardError.write(Data("usage: fix-app-icon <png> <icns> [rail-png]\n".utf8))
    exit(2)
}

let pngURL = URL(fileURLWithPath: arguments[1])
let icnsURL = URL(fileURLWithPath: arguments[2])
let railURL = arguments.count == 4 ? URL(fileURLWithPath: arguments[3]) : nil
guard let source = CGImageSourceCreateWithURL(pngURL as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    throw NSError(domain: "RuleblendIcon", code: 1, userInfo: [NSLocalizedDescriptionKey: "Could not decode \(pngURL.path)"])
}

let width = image.width
let height = image.height
let bytesPerRow = width * 4
let pixels = UnsafeMutablePointer<UInt8>.allocate(capacity: bytesPerRow * height)
pixels.initialize(repeating: 0, count: bytesPerRow * height)
defer { pixels.deallocate() }

let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
guard let context = CGContext(
    data: pixels,
    width: width,
    height: height,
    bitsPerComponent: 8,
    bytesPerRow: bytesPerRow,
    space: colorSpace,
    bitmapInfo: bitmapInfo
) else {
    throw NSError(domain: "RuleblendIcon", code: 2, userInfo: [NSLocalizedDescriptionKey: "Could not create RGBA buffer"])
}
context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))

let edgeThreshold: UInt8 = 12
let backgroundThreshold: UInt8 = 1

for y in 0..<height {
    func offset(at x: Int) -> Int { y * bytesPerRow + x * 4 }
    func intensity(at x: Int) -> UInt8 {
        let offset = offset(at: x)
        let alpha = Double(pixels[offset + 3])
        guard alpha > 0 else { return 0 }
        let value = (0..<3).map { component in
            min(255, Double(pixels[offset + component]) * 255 / alpha)
        }.max() ?? 0
        return UInt8(value.rounded())
    }
    func applyAlpha(_ alpha: UInt8, at x: Int) {
        let offset = offset(at: x)
        let oldAlpha = Double(pixels[offset + 3])
        for component in 0..<3 {
            let straight = oldAlpha == 0 ? 0 : min(255, Double(pixels[offset + component]) * 255 / oldAlpha)
            pixels[offset + component] = UInt8((straight * Double(alpha) / 255).rounded())
        }
        pixels[offset + 3] = alpha
    }
    func edgeAlpha(at x: Int) -> UInt8 {
        let value = intensity(at: x)
        guard value > backgroundThreshold else { return 0 }
        let scale = Double(value - backgroundThreshold) / Double(edgeThreshold - backgroundThreshold)
        return UInt8((min(1, scale) * 255).rounded())
    }

    guard let left = (0..<width).first(where: { intensity(at: $0) >= edgeThreshold }),
          let right = (0..<width).reversed().first(where: { intensity(at: $0) >= edgeThreshold }) else {
        for x in 0..<width { applyAlpha(0, at: x) }
        continue
    }

    for x in 0..<left {
        applyAlpha(edgeAlpha(at: x), at: x)
    }
    if right + 1 < width {
        for x in (right + 1)..<width {
            applyAlpha(edgeAlpha(at: x), at: x)
        }
    }
}

guard let fixedImage = context.makeImage() else {
    throw NSError(domain: "RuleblendIcon", code: 3, userInfo: [NSLocalizedDescriptionKey: "Could not create fixed image"])
}

// Crop to the artwork itself so the step below is idempotent: a PNG that already carries the
// macOS margin is cut back to its silhouette and padded again to the same geometry.
var minX = width, maxX = -1, minY = height, maxY = -1
for y in 0..<height {
    for x in 0..<width where pixels[y * bytesPerRow + x * 4 + 3] > 0 {
        minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
    }
}
guard maxX >= minX, maxY >= minY,
      let artwork = fixedImage.cropping(to: CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)) else {
    throw NSError(domain: "RuleblendIcon", code: 6, userInfo: [NSLocalizedDescriptionKey: "Icon is fully transparent"])
}

// macOS app icons keep the rounded square at 824 of the 1024 canvas; a full-bleed silhouette shows
// up ~24% larger than its neighbours in the Dock and the app switcher.
let canvasSize = 1024
let artworkSize = 824
let inset = (canvasSize - artworkSize) / 2
guard let padded = CGContext(
    data: nil,
    width: canvasSize,
    height: canvasSize,
    bitsPerComponent: 8,
    bytesPerRow: canvasSize * 4,
    space: colorSpace,
    bitmapInfo: bitmapInfo
) else {
    throw NSError(domain: "RuleblendIcon", code: 7, userInfo: [NSLocalizedDescriptionKey: "Could not create padded canvas"])
}
padded.interpolationQuality = .high
padded.draw(artwork, in: CGRect(x: inset, y: inset, width: artworkSize, height: artworkSize))
guard let paddedImage = padded.makeImage() else {
    throw NSError(domain: "RuleblendIcon", code: 8, userInfo: [NSLocalizedDescriptionKey: "Could not create padded image"])
}

func writePNG(_ image: CGImage, to url: URL) throws {
    let temporaryPNG = FileManager.default.temporaryDirectory
        .appendingPathComponent("ruleblend-app-icon-\(UUID().uuidString).png")
    guard let destination = CGImageDestinationCreateWithURL(temporaryPNG as CFURL, "public.png" as CFString, 1, nil) else {
        throw NSError(domain: "RuleblendIcon", code: 4, userInfo: [NSLocalizedDescriptionKey: "Could not create PNG destination"])
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else {
        throw NSError(domain: "RuleblendIcon", code: 5, userInfo: [NSLocalizedDescriptionKey: "Could not encode PNG"])
    }
    try Data(contentsOf: temporaryPNG).write(to: url, options: .atomic)
    try? FileManager.default.removeItem(at: temporaryPNG)
}

try writePNG(paddedImage, to: pngURL)
// The in-app rail logo has no Dock neighbours, so it stays margin-free.
let artworkPNG = FileManager.default.temporaryDirectory
    .appendingPathComponent("ruleblend-app-icon-artwork-\(UUID().uuidString).png")
try writePNG(artwork, to: artworkPNG)
defer { try? FileManager.default.removeItem(at: artworkPNG) }

let fileManager = FileManager.default
let iconset = fileManager.temporaryDirectory
    .appendingPathComponent("ruleblend-app-icon-\(UUID().uuidString).iconset", isDirectory: true)
try fileManager.createDirectory(at: iconset, withIntermediateDirectories: true)
defer { try? fileManager.removeItem(at: iconset) }

let variants = [
    (16, "icon_16x16.png", "ic04"),
    (32, "icon_16x16@2x.png", "ic11"),
    (32, "icon_32x32.png", "ic05"),
    (64, "icon_32x32@2x.png", "ic12"),
    (128, "icon_128x128.png", "ic07"),
    (256, "icon_128x128@2x.png", "ic13"),
    (256, "icon_256x256.png", "ic08"),
    (512, "icon_256x256@2x.png", "ic14"),
    (512, "icon_512x512.png", "ic09"),
    (1024, "icon_512x512@2x.png", "ic10"),
]

func run(_ executable: String, _ arguments: [String]) throws {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: executable)
    process.arguments = arguments
    process.standardOutput = FileHandle.nullDevice
    process.standardError = FileHandle.nullDevice
    try process.run()
    process.waitUntilExit()
    guard process.terminationStatus == 0 else {
        throw NSError(
            domain: "RuleblendIcon",
            code: Int(process.terminationStatus),
            userInfo: [NSLocalizedDescriptionKey: "\(executable) failed"],
        )
    }
}

if let railURL {
    try run("/usr/bin/sips", ["-z", "64", "64", artworkPNG.path, "--out", railURL.path])
}

for (size, name, _) in variants {
    try run(
        "/usr/bin/sips",
        ["-z", String(size), String(size), pngURL.path, "--out", iconset.appendingPathComponent(name).path],
    )
}

func appendBigEndian(_ value: Int, to data: inout Data) {
    var encoded = UInt32(value).bigEndian
    withUnsafeBytes(of: &encoded) { data.append(contentsOf: $0) }
}

var body = Data()
for (_, name, type) in variants {
    let icon = try Data(contentsOf: iconset.appendingPathComponent(name))
    body.append(Data(type.utf8))
    appendBigEndian(icon.count + 8, to: &body)
    body.append(icon)
}
var icns = Data("icns".utf8)
appendBigEndian(body.count + 8, to: &icns)
icns.append(body)
try icns.write(to: icnsURL, options: .atomic)
