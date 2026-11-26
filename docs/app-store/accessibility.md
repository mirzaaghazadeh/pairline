# Accessibility Nutrition Labels

What the App Store product page says Pairline supports, per device family, and why. A label means people can do the
app's common tasks with that setting on (pair, read and send messages, answer and make calls, copy a code, contacts,
settings, buy), so only what was checked is claimed (D-091). Declarations: iPhone
`8058c771-16b3-48c3-af7e-73504e785193`, iPad `3ed43377-53f0-47d2-be13-0e95c71a53d2`
(`asc accessibility list --app 6838659501`).

**Still drafts.** Larger Text, Sufficient Contrast and Reduced Motion come from commit fe82970, which isn't in 1.0.1
build 16 (the build in review). Publish them only once a build with fe82970 is the one on the App Store:
`asc accessibility update --id <id> --publish true` for both.

| Label | Claimed | Evidence (2026-11-26, iPhone 18 Pro Simulator, iOS 27.2) |
|---|---|---|
| Dark Interface | yes | Every screen follows the system appearance; the call screen is always dark |
| Differentiate Without Color Alone | yes | Status also has text or a shape: "Missed" text and icon, numbered SIM badges, "Connected" text, battery percentage |
| Larger Text | yes (from fe82970) | At Accessibility XL (about 235%) nothing a task needs is cut off: Recents and conversation rows stack their details, banners put their buttons under the text, the welcome page scrolls and its title scales, favourite names wrap. The Recents/Keypad segmented control shortens "Keypad", and iOS shows it in full with the Large Content Viewer (touch and hold) |
| Sufficient Contrast | yes, through Increase Contrast (from fe82970) | Apple accepts Increase Contrast when the default falls short. With it on, every brand color has a deeper (light mode) or lighter (dark mode) variant, white text and glyphs sit on `BrandGreenFill`/`BrandRedFill`, avatars darken, and the paywall's offer uses the plain secondary label instead of the vibrant one. Every element XCTest's contrast audit flagged was measured in the screenshots: 5.7:1 to 21:1, except ones behind the tab bar, off screen or covered (false alarms). By default, the system's secondary gray measures 3.4–3.6:1 |
| Reduced Motion | yes (from fe82970) | Slides (pairing steps, toasts, the call bar, the ringer) become fades, the onboarding doesn't slide or zoom, the link dot doesn't pulse, buttons don't shrink when pressed; the data dots in the two-phones illustration already stopped |
| VoiceOver | no | The automated audit found no missing labels or traits on 9 screens in five modes, but no one has done the common tasks with VoiceOver on a real iPhone yet |
| Voice Control | no | Same: needs a pass on a real iPhone |
| Captions, Audio Descriptions | no | Not applicable: the app has no video or recorded audio |

iPad uses the same screens and colors; it wasn't run separately.

## How it was checked

`XCUIApplication.performAccessibilityAudit(for: .all)` on 9 screens in demo mode (onboarding, pairing, Messages, a
conversation, Calls, Contacts, Bridge, Settings, paywall) in light mode, dark mode, at Accessibility XL text, and with
Increase Contrast in light and dark mode, from a temporary UI-test target (not committed) launched with the existing
debug arguments (`-PairlineDemo`, `-PairlineTab`, `-PairlineThread`, `-PairlinePaywall`, `-PairlineResetOnboarding`,
`-PairlinePairingScan`); each flagged element's contrast measured from the screenshot at its frame; every screenshot
looked at. Simulator settings: `xcrun simctl ui <device> appearance dark|light`, `xcrun simctl ui <device>
increase_contrast enabled`, and `-UIPreferredContentSizeCategoryName UICTContentSizeCategoryAccessibilityXL` as a
launch argument.

## Keeping it true

- Text that shows words: text styles or `@ScaledMetric`, no fixed `.system(size:)`; no `lineLimit(1)` on content at the
  accessibility sizes (`AdaptiveStack` and `dynamicTypeSize.isAccessibilitySize`, `Design/Accessibility.swift`).
- Motion: `.transition(.slide(from:reduceMotion:))` and `.animation(.motion(_:reduceMotion:))` instead of `.move` and
  springs; nothing that repeats forever when Reduce Motion is on.
- Color: brand colors from the asset catalog (each has high-contrast variants); `brandGreenFill`/`brandRedFill`/
  `brandFill` behind white text; `Color(.secondaryLabel)` instead of `.secondary` on materials.
- VoiceOver and Voice Control: do the common tasks on a real iPhone with each turned on, fix what's missing, then claim
  them (`asc accessibility update --id <id> --supports-voiceover true --supports-voice-control true`).
