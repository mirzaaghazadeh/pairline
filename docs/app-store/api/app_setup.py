# App-level settings for Pairline in App Store Connect (idempotent): free download (USA base price 0), available in
# every territory except China mainland (CallKit, D-027) and in new territories, and the content-rights declaration.
import os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asc import request  # noqa: E402

APP = "6838659501"


def ok(status, data, what):
    if status >= 300:
        raise SystemExit(f"{what} failed ({status}): {data}")
    return data


def all_pages(path):
    items, url = [], path
    while url:
        data = ok(*request("GET", url), f"GET {url}")
        items += data["data"]
        url = data.get("links", {}).get("next")
    return items


# Price: free.
status, sched = request("GET", f"/v1/appPriceSchedules/{APP}/manualPrices?include=appPricePoint")
if status == 200 and sched.get("data"):
    print("price already set:", [p["attributes"].get("customerPrice") for p in sched.get("included", [])])
else:
    points = all_pages(f"/v1/apps/{APP}/appPricePoints?filter%5Bterritory%5D=USA&limit=200")
    free = next(p for p in points if float(p["attributes"]["customerPrice"]) == 0)
    body = {"data": {"type": "appPriceSchedules",
                     "relationships": {"app": {"data": {"type": "apps", "id": APP}},
                                       "baseTerritory": {"data": {"type": "territories", "id": "USA"}},
                                       "manualPrices": {"data": [{"type": "appPrices", "id": "${p0}"}]}}},
            "included": [{"type": "appPrices", "id": "${p0}", "attributes": {"startDate": None},
                          "relationships": {"appPricePoint": {"data": {"type": "appPricePoints", "id": free["id"]}}}}]}
    ok(*request("POST", "/v1/appPriceSchedules", body), "price schedule")
    print("price: free")

# Availability: everywhere but China mainland.
status, avail = request("GET", f"/v1/apps/{APP}/appAvailabilityV2")
if status == 200 and avail.get("data"):
    print("availability already set")
else:
    territories = [t["id"] for t in all_pages("/v1/territories?limit=200")]
    refs, included = [], []
    for t in territories:
        ref = "${t-" + t + "}"
        refs.append({"type": "territoryAvailabilities", "id": ref})
        included.append({"type": "territoryAvailabilities", "id": ref, "attributes": {"available": t != "CHN"},
                         "relationships": {"territory": {"data": {"type": "territories", "id": t}}}})
    body = {"data": {"type": "appAvailabilities", "attributes": {"availableInNewTerritories": True},
                     "relationships": {"app": {"data": {"type": "apps", "id": APP}},
                                       "territoryAvailabilities": {"data": refs}}},
            "included": included}
    ok(*request("POST", "/v2/appAvailabilities", body), "availability")
    print(f"availability: {len(territories) - 1} of {len(territories)} territories (not CHN)")

# Content rights: Pairline shows only the user's own messages, calls and contacts.
body = {"data": {"type": "apps", "id": APP,
                 "attributes": {"contentRightsDeclaration": "DOES_NOT_USE_THIRD_PARTY_CONTENT"}}}
ok(*request("PATCH", f"/v1/apps/{APP}", body), "content rights")
print("content rights: does not use third-party content")
