# TestFlight external testing for Pairline (idempotent): test information shown to testers, the private Beta App
# Review contact (copied from the App Store review contact, never printed), an external group with a public link,
# the build's "What to Test", and the Beta App Review submission.
# Testers see: the description, "What to Test", the feedback email and the privacy link. They never see the phone
# number; it only goes to Apple's beta reviewers.
# Env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH. Usage: python3 -I testflight.py <build id>
import os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asc import request  # noqa: E402

APP = "6838659501"
VERSION = "f729c8ac-3295-4dc8-8f9a-8f7726c90d15"  # App Store version 1.0 (source of the review contact)
BUILD = sys.argv[1]
GROUP_NAME = "Public Beta"
DESCRIPTION = ("Pairline puts the SIM card of your Android phone on your iPhone or iPad: texts, one-time codes, "
               "calls, contacts, call history and USSD codes, over an end-to-end encrypted Bluetooth link between "
               "your own two phones. Install the free Pairline Bridge app on the Android phone from "
               "https://getpairline.com/download, then pair the two phones with the QR code. No Android phone "
               "at hand? Demo mode shows the whole app with sample data.")
WHAT_TO_TEST = ("First beta. Please try: pairing with the QR code; receiving and sending SMS; copying one-time "
                "codes; incoming calls while the iPhone is locked; calling from the iPhone; contacts and call "
                "history; USSD codes; the widget; and leaving both phones for an hour and checking that messages "
                "still arrive. Call audio still plays on the Android phone in this version. Send feedback with a "
                "screenshot from TestFlight.")


def ok(status, data, what):
    if status >= 300:
        raise SystemExit(f"{what} failed ({status}): {data}")
    return data


# Contact details from the App Store review detail the maintainer filled in.
review = ok(*request("GET", f"/v1/appStoreVersions/{VERSION}/appStoreReviewDetail"), "read review contact")
contact = {k: review["data"]["attributes"].get(k) for k in ("contactFirstName", "contactLastName", "contactPhone", "contactEmail")}
if not all(contact.values()):
    raise SystemExit("The App Store review contact is incomplete; fill it in first.")

# 1. Test information (what testers see), English.
locs = ok(*request("GET", f"/v1/apps/{APP}/betaAppLocalizations"), "beta localizations")["data"]
attrs = {"description": DESCRIPTION, "feedbackEmail": contact["contactEmail"],
         "marketingUrl": "https://getpairline.com", "privacyPolicyUrl": "https://getpairline.com/privacy"}
en = next((l for l in locs if l["attributes"]["locale"] == "en-US"), None)
if en:
    ok(*request("PATCH", f"/v1/betaAppLocalizations/{en['id']}",
                {"data": {"type": "betaAppLocalizations", "id": en["id"], "attributes": attrs}}), "update test info")
else:
    ok(*request("POST", "/v1/betaAppLocalizations",
                {"data": {"type": "betaAppLocalizations", "attributes": {"locale": "en-US", **attrs},
                          "relationships": {"app": {"data": {"type": "apps", "id": APP}}}}}), "create test info")
print("test information set (feedback email = the App Store review contact email)")

# 2. Beta App Review contact (private to Apple) and notes.
notes = ("Pairline is the iPhone half of a two-phone setup; the Android half is our free Pairline Bridge app. "
         "To test without an Android phone: Continue until 'How would you like to start?', choose 'Explore the "
         "demo', then use 'Try it' in the demo banner to receive a sample code, message or call.")
ok(*request("PATCH", f"/v1/betaAppReviewDetails/{APP}",
            {"data": {"type": "betaAppReviewDetails", "id": APP,
                      "attributes": {**contact, "demoAccountRequired": False, "notes": notes}}}), "beta review contact")
print("beta review contact set (private to Apple)")

# 3. What to Test for this build.
blocs = ok(*request("GET", f"/v1/builds/{BUILD}/betaBuildLocalizations"), "build localizations")["data"]
b_en = next((l for l in blocs if l["attributes"]["locale"] == "en-US"), None)
if b_en:
    ok(*request("PATCH", f"/v1/betaBuildLocalizations/{b_en['id']}",
                {"data": {"type": "betaBuildLocalizations", "id": b_en["id"], "attributes": {"whatsNew": WHAT_TO_TEST}}}), "what to test")
else:
    ok(*request("POST", "/v1/betaBuildLocalizations",
                {"data": {"type": "betaBuildLocalizations", "attributes": {"locale": "en-US", "whatsNew": WHAT_TO_TEST},
                          "relationships": {"build": {"data": {"type": "builds", "id": BUILD}}}}}), "what to test")
print("what to test set")

# 4. External group with a public link, and the build in it.
groups = ok(*request("GET", f"/v1/apps/{APP}/betaGroups"), "groups")["data"]
group = next((g for g in groups if g["attributes"]["name"] == GROUP_NAME), None)
if not group:
    group = ok(*request("POST", "/v1/betaGroups",
                        {"data": {"type": "betaGroups",
                                  "attributes": {"name": GROUP_NAME, "publicLinkEnabled": True, "publicLinkLimitEnabled": True,
                                                 "publicLinkLimit": 1000, "feedbackEnabled": True},
                                  "relationships": {"app": {"data": {"type": "apps", "id": APP}}}}}), "create group")["data"]
    print("created external group", group["id"])
status, data = request("POST", f"/v1/betaGroups/{group['id']}/relationships/builds",
                       {"data": [{"type": "builds", "id": BUILD}]})
if status >= 300 and "already" not in str(data).lower():
    raise SystemExit(f"add build to group failed ({status}): {data}")
print("build in group", GROUP_NAME)

# 5. Beta App Review.
subs = ok(*request("GET", f"/v1/betaAppReviewSubmissions?filter%5Bbuild%5D={BUILD}"), "beta submissions")["data"]
if subs:
    print("beta review:", subs[0]["attributes"].get("betaReviewState"))
else:
    sub = ok(*request("POST", "/v1/betaAppReviewSubmissions",
                      {"data": {"type": "betaAppReviewSubmissions",
                                "relationships": {"build": {"data": {"type": "builds", "id": BUILD}}}}}), "submit beta review")
    print("beta review:", sub["data"]["attributes"].get("betaReviewState"))

g = ok(*request("GET", f"/v1/betaGroups/{group['id']}"), "group")["data"]["attributes"]
print("public link:", g.get("publicLink") or "(appears after Beta App Review approves the build)")
