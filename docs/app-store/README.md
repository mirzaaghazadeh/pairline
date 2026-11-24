# App Store listing

Everything the App Store product page needs, kept as source so it can be regenerated after UI changes.

| Path | What |
|------|------|
| `slides.html` | The 7 screenshot slides (headline, subtitle, the real app screen in a phone frame), copy in EN/AR/TR |
| `iap.html` | Promotional images for the two in-app purchases (1024 x 1024, no text, as Apple asks) |
| `render.sh` | Renders everything into `out/` (git-ignored) with headless Chrome and ImageMagick |
| `metadata/` | Name, subtitle, promotional text, description, keywords, URLs, categories, review notes (fastlane `deliver` layout) |
| `upload.sh` | Uploads `metadata/` and the screenshots to App Store Connect with `fastlane deliver` |
| `fonts/` | Vazirmatn (SIL OFL 1.1) for the Arabic slides; English and Turkish use the system font (SF Pro) |

The app screens come from `docs/screenshots/ios/<lang>/` (captured on the iPhone 18 Pro Simulator in demo mode with
`-PairlineMarketing`; see `AGENTS.md`). Retake those first when the UI changes, then run `render.sh`.

## Outputs

- `out/en-US/`, `out/ar-SA/`, `out/tr-TR/`: `large/` 1320 x 2868 ("iPhone with Dynamic Island, large display",
  iPhone 18 Pro Max) and `medium/` 1206 x 2622 ("medium display", the size App Store Connect requires). Seven slides
  each.
- The App Store listing is in English, Arabic and Turkish only (D-033). Other app languages see the English page.
- `out/iap/full.png`, `out/iap/trial.png`: the in-app purchase promotional images.

All images are flattened without an alpha channel; App Store Connect rejects transparency.

## Where things go in App Store Connect

- **Screenshots:** the first slide is what the product page opens with, and the first three are what search results
  show, so slides 1-3 carry the main message (SIM on iPhone, calls, codes). The App Store has no separate "header" or
  "search" image for an app; it uses these.
- **App icon:** comes from the build (`ios/Pairline/Resources/Assets.xcassets/AppIcon.appiconset`, no alpha channel).
- **In-app purchases:** `app.pairline.ios.trial3day` "3-day Trial" (non-consumable, free) and `app.pairline.ios.full`
  "Pairline Full Access" (non-consumable, $11.99). Each needs a review screenshot (use
  `docs/screenshots/ios/en/03-paywall.png`); the promotional images are optional.
- **App Privacy:** "Data Not Collected" (`docs/PRIVACY.md`: nothing leaves the user's two phones).
- **Availability:** remove China mainland (CallKit, D-027).
- **Age rating:** none of the content questions apply.
- **Review notes:** `metadata/review_information/notes.txt` (demo mode, the "Try it" menu, the purchases, CallKit).
  Add the contact name, phone and email in App Store Connect; they're kept out of this public repo.
