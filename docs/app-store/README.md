# App Store listing

Everything the App Store product page needs, kept as source so it can be regenerated after UI changes.

| Path | What |
|------|------|
| `slides.html` | The 7 screenshot slides (headline, subtitle, the real app screen in a phone frame), copy in EN/AR/TR |
| `iap.html` | Promotional images for the two in-app purchases (1024 x 1024, no text, as Apple asks) |
| `render.sh` | Renders everything into `out/` (git-ignored) with headless Chrome and ImageMagick |
| `metadata/` | Name, subtitle, promotional text, description, keywords, URLs, categories, review notes (fastlane `deliver` layout) |
| `upload.sh` | Uploads `metadata/` and the screenshots to App Store Connect with `fastlane deliver` |
| `assets.html`, `upload-assets.sh` | The iOS 27 product-page header and search-results images per locale, uploaded and placed with the `asc` CLI |
| `privacy.json` | The App Privacy declaration ("Data Not Collected"), applied and published with `asc web privacy` (needs an `asc web auth login` session) |
| `../screenshots/ipad/<lang>/` | Real iPad Pro 12.9" captures (2048 x 2732, flattened), uploaded with `asc screenshots upload --device-type APP_IPAD_PRO_3GEN_129` |
| `api/` | Small App Store Connect API scripts (Python standard library + `openssl`): `iap.py` sets up the two in-app purchases, `app_setup.py` the price, availability and content rights. Both are idempotent |

**Credentials** stay outside the repo. The API scripts read `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_PATH` (the
`.p8` file); `upload.sh` reads `ASC_API_KEY_JSON`, a fastlane key file with the key's contents (`key_id`, `issuer_id`,
`key`), kept at `~/.appstoreconnect/pairline.json` with mode 600.
| `fonts/` | Vazirmatn (SIL OFL 1.1) for the Arabic slides; English and Turkish use the system font (SF Pro) |

The app screens come from `docs/screenshots/ios/<lang>/` (captured on the iPhone 18 Pro Simulator in demo mode with
`-PairlineMarketing`; see `AGENTS.md`). Retake those first when the UI changes, then run `render.sh`.

## Outputs

- `out/en-US/`, `out/ar-SA/`, `out/tr/`: `large/` 1320 x 2868 ("iPhone with Dynamic Island, large display",
  iPhone 18 Pro Max) and `medium/` 1206 x 2622 ("medium display", the size App Store Connect requires). Seven slides
  each.
- The App Store listing is in English, Arabic and Turkish only (D-033). Other app languages see the English page.
- `out/iap/full.png`, `out/iap/trial.png`: the in-app purchase promotional images.

All images are flattened without an alpha channel; App Store Connect rejects transparency.

## Where things go in App Store Connect

The app record: Apple ID **6838659501**, bundle ID `app.pairline.ios`, team 7Y68TERDUC (the maintainer's personal
account). "Pairline" alone is taken on the App Store, so the store name is "Pairline: SMS & Call Bridge" (Turkish
"Pairline: SMS ve Arama Köprüsü", Arabic "Pairline: رسائل ومكالمات"); the Home Screen name stays "Pairline".

- **Screenshots:** slides 1-3 carry the main message (SIM on iPhone, calls, codes), since older App Store versions
  show the first three in search results.
- **Product page header and search results image** (iOS 27 Asset Library): `assets.html` renders a text-free header
  (3840 x 1646, three phones centered: the page crops it per device and puts Back and Share over the top corners) and
  a search image (3840 x 2560, headline and two phones) per locale. `upload-assets.sh` (asc CLI) uploads them and
  places each locale's pair. They're reviewed with the version: add them to the review submission
  (`asc review items add --item-type appAssetLibraryImages`). Apple's rules: no price, URL, copyright mark, awards
  or other platforms' logos.
- **App icon:** comes from the build (`ios/Pairline/Resources/Assets.xcassets/AppIcon.appiconset`, no alpha channel).
- **In-app purchases:** `app.pairline.ios.trial3day` "3-day Trial" (non-consumable, free) and `app.pairline.ios.full`
  "Pairline Full Access" (non-consumable, $11.99). Each needs a review screenshot (use
  `docs/screenshots/ios/en/03-paywall.png`); the promotional images are optional.
- **App Privacy:** "Data Not Collected" (`docs/PRIVACY.md`: nothing leaves the user's two phones).
- **Availability:** remove China mainland (CallKit, D-027).
- **Age rating:** none of the content questions apply.
- **Review notes:** `metadata/review_information/notes.txt` (demo mode, the "Try it" menu, the purchases, CallKit).
  Add the contact name, phone and email in App Store Connect; they're kept out of this public repo.
