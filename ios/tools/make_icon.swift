// Draws the Pairline app icon, launch mark and brand mark with CoreGraphics: two links of a chain, one for each
// phone (the "Chain" logo, also on the website and in Telegram). The left link passes over the right one at the top
// and under it at the bottom. Same geometry as pairline-web's public/img/app-icon.svg (512 units, doubled here).
// Dev tool only. Regenerate with:
//   swift ios/tools/make_icon.swift ios/Pairline/Resources/Assets.xcassets
import AppKit
import CoreGraphics
import Foundation

func rgb(_ hex: UInt32) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
}

let blueTop = rgb(0x2A6FD6)
let blueBottom = rgb(0x15468F)
let green = rgb(0x19C994)
let white = rgb(0xFFFFFF)

enum Style { case icon, darkIcon, tintedIcon, launchMark }

func render(size: Int, style: Style) -> Data {
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    // Only the launch mark needs transparency. The icons are opaque and must be written without an alpha channel:
    // App Store Connect rejects an app icon that has one, even when every pixel is opaque (ITMS-90717).
    let alpha: CGImageAlphaInfo = style == .launchMark ? .premultipliedLast : .noneSkipLast
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                        bitmapInfo: alpha.rawValue)!
    let s = CGFloat(size) / 1024
    ctx.scaleBy(x: s, y: s)

    // Background (CoreGraphics' origin is bottom-left, so "top-left" is y = 1024).
    let left: CGColor
    let right: CGColor
    switch style {
    case .icon:
        let gradient = CGGradient(colorsSpace: space, colors: [blueTop, blueBottom] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: 1024), end: CGPoint(x: 1024, y: 0), options: [])
        left = white
        right = green
    case .darkIcon:
        let gradient = CGGradient(colorsSpace: space, colors: [
            CGColor(srgbRed: 0.10, green: 0.12, blue: 0.17, alpha: 1),
            CGColor(srgbRed: 0.04, green: 0.05, blue: 0.08, alpha: 1),
        ] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: 1024), end: CGPoint(x: 1024, y: 0), options: [])
        left = rgb(0x9DC1FF)
        right = green
    case .tintedIcon:
        // iOS tints by luminance: two greys keep the links apart.
        ctx.setFillColor(CGColor(gray: 0, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: 1024, height: 1024))
        left = CGColor(gray: 1, alpha: 1)
        right = CGColor(gray: 0.62, alpha: 1)
    case .launchMark:
        // Transparent, on the launch screen's white (light) or black (dark) background.
        left = blueTop
        right = green
    }

    // The links: 440 × 240 rounded rectangles (fully round ends), 64 wide strokes, overlapping by 200.
    let linkA = CGPath(roundedRect: CGRect(x: 172, y: 392, width: 440, height: 240), cornerWidth: 120, cornerHeight: 120,
                       transform: nil)
    let linkB = CGPath(roundedRect: CGRect(x: 412, y: 392, width: 440, height: 240), cornerWidth: 120, cornerHeight: 120,
                       transform: nil)
    ctx.setLineWidth(64)

    func stroke(_ path: CGPath, _ color: CGColor, topHalfOnly: Bool = false) {
        ctx.saveGState()
        if topHalfOnly { ctx.clip(to: CGRect(x: 0, y: 512, width: 1024, height: 512)) }
        if style == .icon || style == .darkIcon {
            ctx.setShadow(offset: CGSize(width: 0, height: -10), blur: 28,
                          color: CGColor(srgbRed: 0, green: 0.05, blue: 0.2, alpha: 0.30))
        }
        ctx.addPath(path)
        ctx.setStrokeColor(color)
        ctx.strokePath()
        ctx.restoreGState()
    }

    stroke(linkA, left)
    stroke(linkB, right)
    stroke(linkA, left, topHalfOnly: true) // over the right link at the top, under it at the bottom

    let image = ctx.makeImage()!
    let rep = NSBitmapImageRep(cgImage: image)
    return rep.representation(using: .png, properties: [:])!
}

let args = CommandLine.arguments
guard args.count == 2 else {
    print("usage: swift make_icon.swift <path to Assets.xcassets>")
    exit(1)
}
let assets = URL(fileURLWithPath: args[1])
let iconDir = assets.appendingPathComponent("AppIcon.appiconset")
let markDir = assets.appendingPathComponent("LaunchMark.imageset")
let brandDir = assets.appendingPathComponent("BrandMark.imageset")
for dir in [iconDir, markDir, brandDir] {
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
}
try render(size: 1024, style: .icon).write(to: iconDir.appendingPathComponent("AppIcon.png"))
try render(size: 1024, style: .darkIcon).write(to: iconDir.appendingPathComponent("AppIcon-Dark.png"))
try render(size: 1024, style: .tintedIcon).write(to: iconDir.appendingPathComponent("AppIcon-Tinted.png"))
try render(size: 360, style: .launchMark).write(to: markDir.appendingPathComponent("LaunchMark@3x.png"))
try render(size: 540, style: .icon).write(to: brandDir.appendingPathComponent("BrandMark@3x.png"))
print("Wrote icons to \(assets.path)")
