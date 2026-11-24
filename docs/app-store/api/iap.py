# Sets up Pairline's two non-consumable in-app purchases in App Store Connect (idempotent: re-running skips what
# exists): localizations EN/AR/TR, price (USA base, other territories equalized by Apple), availability everywhere
# except China mainland, review screenshot and promotional image.
# Env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH. Usage: python3 -I iap.py
import hashlib, os, sys, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asc import request  # noqa: E402

APP = "6838659501"
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
REVIEW_SHOT = f"{REPO}/docs/screenshots/ios/en/03-paywall.png"
PRODUCTS = [
    {
        "productId": "app.pairline.ios.trial7day", "name": "7-day Trial", "price": "0.0",
        "image": f"{REPO}/docs/app-store/out/iap/trial.png",
        "reviewNote": "Free non-consumable that starts the 7-day trial (App Review Guideline 3.1.1); it needs no "
                      "payment method. When the 7 days end, the app asks the user to buy Pairline Full Access. "
                      "Nothing renews or is charged.",
        "loc": {
            "en-US": ("7-day Trial", "Try every Pairline feature free for 7 days."),
            "ar-SA": ("تجربة لمدة 7 أيام", "جرّب كل ميزات Pairline مجانًا لمدة 7 أيام."),
            "tr": ("7 Günlük Deneme", "Tüm özellikleri 7 gün ücretsiz deneyin."),
        },
    },
    {
        "productId": "app.pairline.ios.full", "name": "Pairline Full Access", "price": "9.99",
        "image": f"{REPO}/docs/app-store/out/iap/full.png",
        "reviewNote": "One-time purchase that unlocks Pairline permanently. Restore Purchases is on the start "
                      "screen, the paywall and in Settings.",
        "loc": {
            "en-US": ("Pairline Full Access", "Unlock Pairline for good with one payment."),
            "ar-SA": ("الوصول الكامل إلى Pairline", "افتح Pairline للأبد بدفعة واحدة."),
            "tr": ("Pairline Tam Erişim", "Pairline'ı tek ödemeyle kalıcı olarak açın."),
        },
    },
]


def ok(status, data, what):
    if status >= 300:
        raise SystemExit(f"{what} failed ({status}): {data}")
    return data


def all_pages(path):
    items, url = [], path
    while url:
        status, data = request("GET", url)
        ok(status, data, f"GET {url}")
        items += data["data"]
        url = data.get("links", {}).get("next")
    return items


def upload_asset(create_path, rel_name, rel_type, owner_id, file_path, asset_type):
    blob = open(file_path, "rb").read()
    body = {"data": {"type": asset_type,
                     "attributes": {"fileName": os.path.basename(file_path), "fileSize": len(blob)},
                     "relationships": {rel_name: {"data": {"type": rel_type, "id": owner_id}}}}}
    data = ok(*request("POST", create_path, body), f"reserve {asset_type}")
    asset_id = data["data"]["id"]
    for op in data["data"]["attributes"]["uploadOperations"]:
        chunk = blob[op["offset"]:op["offset"] + op["length"]]
        req = urllib.request.Request(op["url"], data=chunk, method=op["method"])
        for h in op.get("requestHeaders") or []:
            req.add_header(h["name"], h["value"])
        with urllib.request.urlopen(req, timeout=120) as r:
            assert r.status < 300, r.status
    patch = {"data": {"type": asset_type, "id": asset_id,
                      "attributes": {"uploaded": True, "sourceFileChecksum": hashlib.md5(blob).hexdigest()}}}
    ok(*request("PATCH", f"{create_path}/{asset_id}", patch), f"commit {asset_type}")
    print(f"  uploaded {os.path.basename(file_path)} as {asset_type}")


territories = [t["id"] for t in all_pages("/v1/territories?limit=200")]
available = [t for t in territories if t != "CHN"]
print(f"{len(territories)} territories; offering in {len(available)} (all but CHN)")

existing = {i["attributes"]["productId"]: i for i in all_pages(f"/v1/apps/{APP}/inAppPurchasesV2?limit=200")}
for p in PRODUCTS:
    print(p["productId"])
    if p["productId"] in existing:
        iap_id = existing[p["productId"]]["id"]
        print("  exists", iap_id)
    else:
        body = {"data": {"type": "inAppPurchases",
                         "attributes": {"name": p["name"], "productId": p["productId"],
                                        "inAppPurchaseType": "NON_CONSUMABLE", "familySharable": False,
                                        "reviewNote": p["reviewNote"]},
                         "relationships": {"app": {"data": {"type": "apps", "id": APP}}}}}
        iap_id = ok(*request("POST", "/v2/inAppPurchases", body), "create IAP")["data"]["id"]
        print("  created", iap_id)

    have = {l["attributes"]["locale"] for l in all_pages(f"/v2/inAppPurchases/{iap_id}/inAppPurchaseLocalizations")}
    for locale, (name, desc) in p["loc"].items():
        if locale in have:
            continue
        assert len(name) <= 30 and len(desc) <= 45, (locale, name, desc)
        body = {"data": {"type": "inAppPurchaseLocalizations",
                         "attributes": {"locale": locale, "name": name, "description": desc},
                         "relationships": {"inAppPurchaseV2": {"data": {"type": "inAppPurchases", "id": iap_id}}}}}
        ok(*request("POST", "/v1/inAppPurchaseLocalizations", body), f"localization {locale}")
        print("  localization", locale)

    points = all_pages(f"/v2/inAppPurchases/{iap_id}/pricePoints?filter%5Bterritory%5D=USA&limit=200")
    point = next(pp for pp in points if float(pp["attributes"]["customerPrice"]) == float(p["price"]))
    status, sched = request("GET", f"/v2/inAppPurchases/{iap_id}/iapPriceSchedule?include=manualPrices")
    current = {(x.get("relationships", {}).get("inAppPurchasePricePoint", {}).get("data") or {}).get("id")
               for x in (sched.get("included", []) if status == 200 else [])}
    if point["id"] in current:
        print("  price already", p["price"])
    else:
        # Posting a schedule replaces the previous one (a price change takes effect right away).
        body = {"data": {"type": "inAppPurchasePriceSchedules",
                         "relationships": {"inAppPurchase": {"data": {"type": "inAppPurchases", "id": iap_id}},
                                           "baseTerritory": {"data": {"type": "territories", "id": "USA"}},
                                           "manualPrices": {"data": [{"type": "inAppPurchasePrices", "id": "${p0}"}]}}},
                "included": [{"type": "inAppPurchasePrices", "id": "${p0}", "attributes": {"startDate": None},
                              "relationships": {"inAppPurchasePricePoint": {"data": {"type": "inAppPurchasePricePoints", "id": point["id"]}}}}]}
        ok(*request("POST", "/v1/inAppPurchasePriceSchedules", body), "price schedule")
        print("  price USD", point["attributes"]["customerPrice"])

    status, avail = request("GET", f"/v2/inAppPurchases/{iap_id}/inAppPurchaseAvailability")
    if status == 200 and avail.get("data"):
        print("  availability already set")
    else:
        body = {"data": {"type": "inAppPurchaseAvailabilities", "attributes": {"availableInNewTerritories": True},
                         "relationships": {"inAppPurchase": {"data": {"type": "inAppPurchases", "id": iap_id}},
                                           "availableTerritories": {"data": [{"type": "territories", "id": t} for t in available]}}}}
        ok(*request("POST", "/v1/inAppPurchaseAvailabilities", body), "availability")
        print("  availability set")

    status, shot = request("GET", f"/v2/inAppPurchases/{iap_id}/appStoreReviewScreenshot")
    if status == 200 and shot.get("data"):
        print("  review screenshot already there")
    else:
        upload_asset("/v1/inAppPurchaseAppStoreReviewScreenshots", "inAppPurchaseV2", "inAppPurchases", iap_id,
                     REVIEW_SHOT, "inAppPurchaseAppStoreReviewScreenshots")

    images = all_pages(f"/v2/inAppPurchases/{iap_id}/images")
    if images:
        print("  promotional image already there")
    else:
        upload_asset("/v1/inAppPurchaseImages", "inAppPurchase", "inAppPurchases", iap_id, p["image"], "inAppPurchaseImages")

print("done")
