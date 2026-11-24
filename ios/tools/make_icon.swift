// Draws the Pairline app icon and launch mark with CoreGraphics: two phones joined by a line.
// Dev tool only. Regenerate with:
//   swift ios/tools/make_icon.swift ios/Pairline/Resources/Assets.xcassets
import AppKit
import CoreGraphics
import Foundation

let blue = CGColor(srgbRed: 0x1F / 255, green: 0x5F / 255, blue: 0xBF / 255, alpha: 1)
let blueLight = CGColor(srgbRed: 0x3D / 255, green: 0x7F / 255, blue: 0xE0 / 255, alpha: 1)
let blueDeep = CGColor(srgbRed: 0x16 / 255, green: 0x46 / 255, blue: 0x93 / 255, alpha: 1)
let green = CGColor(srgbRed: 0x12 / 255, green: 0xB8 / 255, blue: 0x86 / 255, alpha: 1)
let white = CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1)

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

    let phoneFill: CGColor
    let screenTop: CGColor
    let screenBottom: CGColor
    let lineColor: CGColor
    switch style {
    case .icon:
        let gradient = CGGradient(colorsSpace: space, colors: [blueLight, blue, blueDeep] as CFArray,
                                  locations: [0, 0.55, 1])!
        ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: 1024), end: CGPoint(x: 1024, y: 0), options: [])
        phoneFill = white
        screenTop = CGColor(srgbRed: 0xE7 / 255, green: 0xF0 / 255, blue: 0xFF / 255, alpha: 1)
        screenBottom = CGColor(srgbRed: 0xC9 / 255, green: 0xDC / 255, blue: 0xFB / 255, alpha: 1)
        lineColor = white
    case .darkIcon:
        let gradient = CGGradient(colorsSpace: space, colors: [
            CGColor(srgbRed: 0.10, green: 0.12, blue: 0.17, alpha: 1),
            CGColor(srgbRed: 0.04, green: 0.05, blue: 0.08, alpha: 1),
        ] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: 1024), end: CGPoint(x: 1024, y: 0), options: [])
        phoneFill = CGColor(srgbRed: 0x9D / 255, green: 0xC1 / 255, blue: 0xFF / 255, alpha: 1)
        screenTop = CGColor(srgbRed: 0.13, green: 0.20, blue: 0.33, alpha: 1)
        screenBottom = CGColor(srgbRed: 0.08, green: 0.13, blue: 0.24, alpha: 1)
        lineColor = phoneFill
    case .tintedIcon:
        ctx.setFillColor(CGColor(gray: 0, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: 1024, height: 1024))
        phoneFill = CGColor(gray: 1, alpha: 1)
        screenTop = CGColor(gray: 0.35, alpha: 1)
        screenBottom = CGColor(gray: 0.25, alpha: 1)
        lineColor = phoneFill
    case .launchMark:
        phoneFill = blue
        screenTop = CGColor(srgbRed: 0xE7 / 255, green: 0xF0 / 255, blue: 0xFF / 255, alpha: 1)
        screenBottom = CGColor(srgbRed: 0xD3 / 255, green: 0xE3 / 255, blue: 0xFC / 255, alpha: 1)
        lineColor = blue
    }

    // Soft shadow under the phones on the icon styles.
    func phone(_ x: CGFloat) {
        let body = CGRect(x: x, y: 262, width: 260, height: 500)
        let path = CGPath(roundedRect: body, cornerWidth: 70, cornerHeight: 70, transform: nil)
        ctx.saveGState()
        if style != .launchMark && style != .tintedIcon {
            ctx.setShadow(offset: CGSize(width: 0, height: -14), blur: 36,
                          color: CGColor(srgbRed: 0, green: 0.05, blue: 0.2, alpha: 0.35))
        }
        ctx.addPath(path)
        ctx.setFillColor(phoneFill)
        ctx.fillPath()
        ctx.restoreGState()

        let screen = body.insetBy(dx: 22, dy: 22)
        let screenPath = CGPath(roundedRect: screen, cornerWidth: 50, cornerHeight: 50, transform: nil)
        ctx.saveGState()
        ctx.addPath(screenPath)
        ctx.clip()
        let g = CGGradient(colorsSpace: space, colors: [screenTop, screenBottom] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: screen.maxY), end: CGPoint(x: 0, y: screen.minY), options: [])
        ctx.restoreGState()

        // Speaker slot
        let slot = CGRect(x: body.midX - 34, y: body.maxY - 44, width: 68, height: 12)
        ctx.addPath(CGPath(roundedRect: slot, cornerWidth: 6, cornerHeight: 6, transform: nil))
        ctx.setFillColor(phoneFill)
        ctx.fillPath()
    }

    phone(150)
    phone(614)

    // The line joining them: on the screens it takes the screen-ink color, between the phones the body color.
    let screenInk = style == .icon ? blue : lineColor
    let line = CGRect(x: 280, y: 491, width: 464, height: 42)
    ctx.addPath(CGPath(roundedRect: line, cornerWidth: 21, cornerHeight: 21, transform: nil))
    ctx.setFillColor(screenInk)
    ctx.fillPath()
    ctx.setFillColor(phoneFill)
    ctx.fill(CGRect(x: 410, y: 491, width: 204, height: 42))

    // Dots at each end, on the screens.
    for cx in [CGFloat(280), CGFloat(744)] {
        ctx.addEllipse(in: CGRect(x: cx - 40, y: 512 - 40, width: 80, height: 80))
        ctx.setFillColor(screenInk)
        ctx.fillPath()
    }

    // The "connected" node in the middle.
    let node = CGRect(x: 512 - 70, y: 512 - 70, width: 140, height: 140)
    ctx.addEllipse(in: node)
    ctx.setFillColor(style == .tintedIcon ? CGColor(gray: 0.85, alpha: 1) : green)
    ctx.fillPath()
    ctx.addEllipse(in: node.insetBy(dx: 9, dy: 9))
    ctx.setStrokeColor(style == .launchMark ? white : (style == .icon ? white : phoneFill))
    ctx.setLineWidth(18)
    ctx.strokePath()

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
