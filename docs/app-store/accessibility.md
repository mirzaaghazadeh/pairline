# Accessibility Nutrition Labels

What the App Store product page says Pairline supports, per device family, and why. A label means people can do the
app's common tasks with that setting on (pair, read and send messages, answer and make calls, copy a code, contacts,
settings, buy), so only what was checked is claimed. Declarations: iPhone `8058c771-16b3-48c3-af7e-73504e785193`,
iPad `3ed43377-53f0-47d2-be13-0e95c71a53d2` (`asc accessibility list --app 6838659501`); drafts until published with
`asc accessibility update --id <id> --publish true`.

| Label | Claimed | Evidence (2026-11-26, commit abcad73, iPhone 18 Pro Simulator, iOS 27.2) |
|---|---|---|
| Dark Interface | yes | Every screen follows the system appearance; the call screen is always dark |
| Differentiate Without Color Alone | yes | Status also has text or a shape: "Missed" text and icon, numbered SIM badges, "Connected" text, battery percentage |
| Larger Text | no | At Accessibility XL (about 235%) text grows but content is lost: the onboarding description is cut off, numbers and names in Recents are truncated, call details wrap mid-word; the demo banner and contact initials have fixed sizes |
| Sufficient Contrast | no | XCTest's contrast audit: 31 failures in light mode, 26 in dark — paywall feature descriptions, the pairing screen's buttons, the Bridge illustration's labels and SIM details, the onboarding subtitle, "Edit" in Messages |
| Reduced Motion | no | Only `TwoPhonesIllustration` reads `accessibilityReduceMotion`; the pairing steps, toasts and the ringer slide in, and a pulse repeats forever |
| VoiceOver | no | The automated audit found no missing labels or traits on 9 screens in three modes, but no one has done the common tasks with VoiceOver on a real iPhone yet |
| Voice Control | no | Same: needs a pass on a real iPhone |
| Captions, Audio Descriptions | no | Not applicable: the app has no video or recorded audio |

## How it was checked

`XCUIApplication.performAccessibilityAudit(for: .all)` on 9 screens in demo mode (onboarding, pairing, Messages, a
conversation, Calls, Contacts, Bridge, Settings, paywall), each in light mode, dark mode and at Accessibility XL text,
from a temporary UI-test target (not committed) launched with the existing debug arguments (`-PairlineDemo`,
`-PairlineTab`, `-PairlineThread`, `-PairlinePaywall`, `-PairlineResetOnboarding`, `-PairlinePairingScan`), plus the
screenshots of each.

## To claim more

1. **Larger Text:** let rows stack vertically at accessibility sizes (`dynamicTypeSize.isAccessibilitySize`) in Recents,
   the message list and the demo banner; remove the line limit on the onboarding description; use text styles for the
   contact initials and the remaining fixed sizes that show words.
2. **Sufficient Contrast:** darker secondary text on tinted backgrounds (paywall, pairing, Bridge); check again with
   Increase Contrast on.
3. **Reduced Motion:** read `accessibilityReduceMotion` and use opacity instead of slides, no repeating pulse.
4. **VoiceOver and Voice Control:** do the common tasks on a real iPhone with each turned on, fix what's missing, then
   claim them.

Update the declarations (`asc accessibility update --id <id> --supports-… true`) after each is fixed and checked.
