# App Store listing

Everything the App Store product page needs, kept as source so it can be regenerated after UI changes.

| Path | What |
|------|------|
| `slides.html` | The 7 screenshot slides (headline, subtitle, the real app screen in a device frame), copy in EN/AR/TR; `#device=ipad` and `#device=duo` lay the same slides out for iPad 13-inch and iPhone Duo |
| `iap.html` | Promotional images for the two in-app purchases (1024 x 1024, no text, as Apple asks) |
| `render.sh` | Renders everything into `out/` (git-ignored) with headless Chrome and ImageMagick |
| `metadata/` | Name, subtitle, promotional text, description, keywords, URLs, categories, review notes (fastlane `deliver` layout) |
| `upload.sh` | Uploads `metadata/` and the screenshots to App Store Connect with `fastlane deliver` |
| `assets.html`, `upload-assets.sh` | The iOS 27 product-page header and search-results images per locale, uploaded and placed with the `asc` CLI |
| `events/<event>/`, `events.html`, `upload-event.sh` | In-app events: each folder has `event.env` (type, purpose, priority, deep link, dates) and the name and descriptions per locale; `events.html` renders the text-free card and details-page images; `upload-event.sh` creates or updates the event with the `asc` CLI and never submits it |
| `custom-pages/<page>/`, `upload-custom-page.sh` | Custom product pages: `page.env` (name, slide order) and each locale's promotional text and search keywords; `upload-custom-page.sh` fills in whatever is missing in App Store Connect (localizations, screenshots in that order, keywords) and never submits |
| `accessibility.md` | The Accessibility Nutrition Labels: what's claimed per device family, the audit behind it, and what to fix to claim more |
| `privacy.json` | The App Privacy declaration ("Data Not Collected"), applied and published with `asc web privacy` (needs an `asc web auth login` session) |
| `../screenshots/ipad/<lang>/` | iPad Pro 13-inch (M5) Simulator captures (2064 x 2752, flattened), framed by `slides.html#device=ipad` |
| `../screenshots/duo/<lang>/` | iPhone Duo Simulator captures of the inner display, open, in landscape (2853 x 2007), framed by `slides.html#device=duo` |
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
- `out/<locale>/ipad/`: 2064 x 2752 (iPad 13-inch, `APP_IPAD_PRO_3GEN_129`). `out/<locale>/duo/`: 2853 x 2007
  (iPhone Duo inner display, `APP_IPHONE_DUO`; required from April 2027 for apps built with the iOS 27.1 SDK or later,
  and they add the "made for iPhone Duo" badge to the product page). Upload each with
  `asc screenshots upload --app 6838659501 --version <v> --locale <locale> --path out/<locale>/<device> --device-type <type> --replace --confirm`.
- Capturing: demo mode with `-PairlineMarketing` and the status bar at 9:41 (`xcrun simctl status_bar … override`).
  The iPhone Duo screens are taken with the device open in Device Hub (`simctl io … screenshot --display=<inner>`).
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
- **In-app purchases:** `app.pairline.ios.trial7day` "7-day Trial" (non-consumable, free) and `app.pairline.ios.full`
  "Pairline Full Access" (non-consumable, $9.99) (D-036). Each needs a review screenshot (use
  `docs/screenshots/ios/en/03-paywall.png`); the promotional images are optional.
- **In-app events:** cards on the product page, in search and on the Today tab, for something new in the app (here
  only major updates; a price drop alone doesn't qualify). No code and no data flow: "Notify me" is sent by the App
  Store, and the counts stay in App Store Connect. `render.sh events`, then
  `ASC_PROFILE=<profile> ./upload-event.sh <event>`. Text limits: name 30, short description 50, long description 120
  characters (the script checks them). An event runs at most 31 days and can show up to 14 days before it starts; set
  the purchase requirement in App Store Connect (the CLI only offers "no cost associated"), then submit with
  `asc app-events submit --event-id <id> --app 6838659501 --confirm`. Only features that arrive *after* launch qualify:
  1.0.1 (build 16) already has call audio, internet sharing and file transfer, so those belong on custom product pages,
  not in events. Drafts: **`carrier-tools`** (event 6839730908; D-082, not in build 16) and **`talk-on-iphone`** (event
  6839726175; use it only if a later update is what makes call audio work on real phones). Set dates and submit each
  only after its feature passes the real-phone tests and ships in an App Store version.
- **Custom product pages:** "One-time codes", "Travel with two phones" and "Two numbers", each in EN/AR/TR with
  screenshots for all four sizes, waiting to be submitted. Link to one with the page's `?ppid=` URL (App Store Connect
  shows it). Search keywords can only be added once the app is released in that locale; then run
  `ASC_PROFILE=<profile> ./upload-custom-page.sh <page>` for each page to add them. A keyword leads to one page only.
- **App Privacy:** "Data Not Collected" (`docs/PRIVACY.md`: nothing leaves the user's two phones).
- **Availability:** remove China mainland (CallKit, D-027).
- **Age rating:** none of the content questions apply.
- **Review notes:** `metadata/review_information/notes.txt` (demo mode, the "Try it" menu, the purchases, CallKit).
  Add the contact name, phone and email in App Store Connect; they're kept out of this public repo.
