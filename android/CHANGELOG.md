# Pairline Bridge changelog

What's new in each release of the Android app, newest first, written for people who use it. The release workflow
copies the sections into the GitHub release notes, with everything since the last published release and the list of
contributors (`.github/scripts/release-notes.sh`, D-080). A tag whose version has no section here doesn't build: add
the section in the same commit that raises `versionName`.

## 1.9.5 (2026-11-27)

- **Scanning an invitation works.** "Pair over the internet" › Scan now asks for the camera; in 1.9.4 it couldn't, and
  Camera didn't show in the app's permissions. The camera is used only while the scanner is open, and nothing it sees
  is kept. Choosing a picture of the code or pasting its link still work without it.

## 1.9.4 (2026-11-27)

- **Pairing fits on one screen.** A smaller QR code, the timer and "New code" side by side, and the "keep this screen
  open" note right under the code, so nothing important hides below the fold.
- **Pair over the internet from here.** "Phones not together? Pair over the internet" is now a button on the pairing
  screen, for an iPhone that's somewhere else: scan the invitation's QR code, choose a picture of it, or paste its link.

## 1.9.3 (2026-11-27)

- **Texts from your iPhone go out within seconds in Cloud mode.** This phone now checks iCloud every 3 seconds while
  it's on its charger, and every 5 to 10 seconds on battery, and stays awake enough to do it with the screen off.
  Before, the first text after a quiet spell could wait two minutes or more. Keep this phone on its charger for the
  fastest texts and the least battery worry.

## 1.9.2 (2026-11-27)

- **Diagnostics show Cloud mode.** The report you can share from Settings › Help › Diagnostics now says whether Cloud
  mode is on, when this phone last checked iCloud and last got a message from your iPhone through it, any iCloud
  problem, and whether calls from far away can work (and why not). Its log says which kinds of message came through
  iCloud and why any were set aside (for example, a clock that's off). Still no numbers, addresses or message text.

## 1.9.1 (2026-11-27)

- **"Check my home connection" tells carrier-grade NAT apart.** When the router is slow to answer the check, it now
  keeps what this phone already learned from the router, so a home behind the provider's shared address (carrier-grade
  NAT) hears that, not "turn on UPnP" when UPnP is already on.
- **Help for calls from far away:** how to recognize carrier-grade NAT (your router's internet address starts with
  100.64 to 100.127), why the router's UPnP list stays empty then, what to ask your provider for, double NAT, the exact
  port-forwarding values (TCP 47830 to this phone), and how fast texts go through iCloud.
- Built with the newest Android libraries.

## 1.9.0 (2026-11-27)

- **Nearby or Cloud mode, explained.** Setup has a new "Two ways to connect" step: Nearby works by itself with the
  phones near each other; Cloud mode, turned on in Pairline on your iPhone, is for leaving this phone at home. The home
  screen and the top of Settings say in plain words how the phones are connected right now ("Nearby · Bluetooth",
  "Cloud mode · through iCloud"), and what to do when something's off.
- **A Cloud mode screen** (Settings › Cloud mode): what this phone needs at home (its charger, home Wi-Fi, running in
  the background), each with a Fix button, whether calls from far away work, and the router details that used to fill
  the home screen.
- Help starts with "Nearby or Cloud mode". Buttons before Android's own permission windows say "Continue" instead of
  "Allow", and Diagnostics moved to Settings › Help.
- Calls in Cloud mode while you're away from home were fixed in Pairline for iPhone (TestFlight build 23 and later);
  nothing to change on this phone.

## 1.8.0 (2026-11-26)

- **Cloud mode (testing, with Pairline for iPhone from TestFlight).** Leave this phone at home: when the phones aren't
  near each other, texts, one-time codes and calls keep reaching your iPhone over the internet, through your own
  iCloud, locked so that only your two phones can read them. There's no Pairline server. Where your home router allows
  it (UPnP or NAT-PMP), Pairline also opens one port that only your paired iPhone can use, for a direct connection
  that calls need; the home screen says whether it's open, and what to change in the router or forward by hand if
  not. It's off until you turn it on in Pairline on your iPhone.
- **Pair over the internet.** Open an invitation from your own iPhone here, then check that both phones show the same
  6-digit code.
- **This phone can't be found over Bluetooth?** Some older phones can't advertise over Bluetooth; the home screen now
  says so and what still works (Wi-Fi while Pairline is open on the iPhone, and Cloud mode).
- Pairline Bridge now stays upright (portrait only).
- **Starts in your language.** On a new install, Pairline picks Persian, Arabic or Turkish from your SIM card's
  country (or the network's, or the time zone; Tehran's time zone always gives Persian) when the phone itself is in
  English. Setup now opens with a Language step, so you can see the choice and change it.
- **Call settings from the iPhone.** Call forwarding, call waiting and caller ID, and hiding your number on a single
  call. Forwarding to a number that starts with + works too.
- **Voicemail.** The iPhone hears when a voicemail is waiting on a SIM and can call your voicemail on that SIM.
- **More signal detail on the iPhone:** strength in dBm, the exact network type (such as LTE-CA or 5G NSA) and the
  network you're on when roaming.
- **Scheduled messages.** Messages you schedule on the iPhone are kept here and sent at the time you chose, even if
  the iPhone is off. On Android 13 and later, allow Pairline Bridge under Alarms & reminders so they go out on the
  minute (a notification leads there).
- **Ring my iPhone.** From the home screen or a new Quick Settings tile, make the iPhone play a sound for 30 seconds.
- **Mobile data meter.** The iPhone can show how much mobile data this phone used this month, once you give Pairline
  Bridge Usage access. Only the total goes to the iPhone.
- **Blocked calls don't ring at all.** In Blocked numbers, make Pairline Bridge Android's call-screening app (Android
  10 and later): blocked numbers are turned away before they ring, and **Silence unknown callers** sends calls from
  numbers that aren't in your contacts to voicemail on both phones. Block whole ranges by ending a number with *, such
  as +234*.
- **Reply to app notifications from the iPhone.** Notifications from apps you chose (WhatsApp, Telegram…) get a Reply
  field on the iPhone; the reply goes through that app on this phone, as if you had typed it here.
- **Photos for the iPhone** (Settings, off by default): browse this phone's photos on the iPhone and copy the ones you
  tap, over Wi-Fi only.
- **Picture and group messages (MMS).** They show on the iPhone with their pictures and everyone in the group, and the
  iPhone can send a picture or a group message through this phone. Sending MMS needs mobile data on.

## 1.7.0 (2026-11-26)

- **Notifications from your Android apps on the iPhone.** Choose apps in Settings › Notifications from apps and their
  notifications show up on the iPhone too. Clearing one on either phone clears it on the other. Needs Android's
  notification access, which the screen asks for.
- **Root mode.** On a rooted phone, Settings › Advanced › Root mode starts Talk on iPhone's helper through the phone's
  own `su` instead of Wireless debugging. The helper still runs as the shell user, never as root.
- **"App was denied access" when allowing SMS.** Android 13 and later blocks SMS access for apps installed from a
  file. Pairline now spots this and walks you through App info › Allow restricted settings.
- **Bluetooth that gets stuck.** Settings › Advanced › Restart Bluetooth when stuck (off by default): when Android's
  Bluetooth stops working for Pairline three times in ten minutes while the iPhone isn't connected, Pairline turns
  Bluetooth off and on. Needs Talk on iPhone, and happens at most once an hour.

## 1.6.0 (2026-11-26)

- **Hold and call waiting.** Put a call on hold from the iPhone, and answer or swap a second call while you're on one.
- **Find my phone.** The iPhone can ring this phone loudly for up to a minute, even when it's silent. Your volume is
  put back afterwards.
- **Ring, vibrate or silent from the iPhone.** Changing to silent needs Do Not Disturb access, which Pairline asks for
  the first time.
- **Read on the iPhone, read here.** Conversations you open on the iPhone are marked read on this phone too (with Talk
  on iPhone).
- **Edit contacts from the iPhone:** names, numbers and favorites.
- **Step-by-step help in the app** for turning on Developer options, Wireless debugging and keeping Pairline running,
  instead of videos.
- Works with the iPhone app's calls from Recents, Siri and call links, replies from notifications and muted
  conversations.

## 1.5.0 (2026-11-26)

- **Internet switches on the iPhone.** With Talk on iPhone set up, the iPhone can turn this phone's hotspot,
  Bluetooth tethering and mobile data on and off, and choose which SIM is used for data. They change only when you
  flip the switch on the iPhone.
- The iPhone shows whether Bluetooth tethering is on, with help for setting it up.

## 1.4.0 (2026-11-26)

- **Call audio over Bluetooth.** Talk on iPhone no longer needs the two phones on the same Wi-Fi: the call's audio can
  travel over the Bluetooth link, compressed with Opus (Android's own codec, no new library). Choose Bluetooth or
  Wi-Fi in the iPhone's Settings › Call audio; if the one you chose isn't available, the other is used.
- If Android refuses one way of capturing call audio, Pairline now tries the other.

## 1.3.1 (2026-11-25)

- **Call audio works on Android 15.** Pairline's helper now opens the call's audio as itself; on some phones Android
  refused it before, so calls stayed silent on the iPhone. A failure on one call is no longer remembered for the next.
- **Ring only on the iPhone (on by default).** While your iPhone is connected, incoming calls ring there and this phone
  stays quiet; you can still answer on either phone. Needs Talk on iPhone. Calls from blocked numbers still ring here.
- **The caller's number and name show on the iPhone.** On Android 12 and later some incoming calls appeared as
  "Unknown".
- **Easier Talk on iPhone pairing.** "Pair" opens the Wireless debugging page itself, the "Type the pairing code"
  notification pops up again while it waits and is no longer hidden under Pairline's other notification, and "Forget
  pairing" asks first.
- **Away from Wi-Fi: this phone's hotspot.** Settings › Wi-Fi link leads with this phone's own hotspot and says whether
  the iPhone gets internet over it, and why not (no mobile data, or a VPN, which Android doesn't share over a hotspot;
  Internet sharing does). Pairline's own no-internet hotspot moved under a fold.

## 1.3.0 (2026-11-25)

- **Talk on iPhone keeps working by itself.** Android switches Wireless debugging off at every restart and when Wi-Fi
  drops. Pairline now turns Developer options, USB debugging and Wireless debugging back on whenever its helper needs
  them, as soon as this phone is on Wi-Fi. You set it up once. Turning Talk on iPhone off, or Forget pairing, stops
  this.
- **Call audio on more phones.** Pairline's helper now identifies itself correctly to Android's audio system (on some
  Samsung phones the call audio couldn't start), and this phone's earpiece is silent while the call plays on the
  iPhone.
- **Fewer drops and cleaner audio.** This phone holds its Bluetooth link to the iPhone steady during a session (helps
  when the phones are also paired for Bluetooth tethering), and short Wi-Fi stalls no longer cut words.
- **Internet sharing keeps its address.** The proxy URL you paste on the iPhone (port 47830) and the Telegram proxy
  details stay the same each time you turn sharing on.
- Pairing with Wireless debugging from the notification is more reliable, and a failed call-audio start now says
  exactly what each method did.

## 1.2.3 (2026-11-25)

- **The caller can hear you on more phones.** During Talk on iPhone, this phone's own microphone now stays on by
  default: on several phones (some Samsung, Qualcomm and MediaTek models) muting it also silenced your iPhone's voice.
  Pairline still adjusts this after a call if the other side couldn't hear you.

## 1.2.2 (2026-11-25)

- **Call audio on the iPhone works on more phones.** Pairline now waits for Android to switch the call's audio on
  before capturing it (it used to try too early while a call was dialing, then give up). On Android 13 and later it
  uses Android's own call-audio interception where the phone allows it, and it tries every other method before falling
  back to the Android phone.
- **Stop Pairline.** Settings › Keep running › Stop Pairline turns the bridge off (the link, hotspot, internet sharing
  and the Talk on iPhone helper) until you tap Start Pairline, even after a restart.
- **No more crash when background activity is restricted.** The home screen now warns when Android restricts
  Pairline's background activity and takes you to the setting that fixes it.
- **Talk on iPhone needs Android 11 or later.** Android 10 doesn't let any app capture call audio, so the setup now
  says that instead of offering a computer command.

## 1.2.1 (2026-11-24)

- **Talk on iPhone tells you why the audio stays here.** The Talk on iPhone screen now shows whether the iPhone is
  linked over Wi-Fi, which carries call audio, and what to do if not (same Wi-Fi, Android's own hotspot, the iPhone's
  Local Network permission).
- **Using this phone's hotspot:** Wireless debugging only works on a Wi-Fi network, so turning on the hotspot turns it
  off. Start Pairline's helper on Wi-Fi first; it keeps running after you switch to the hotspot.

## 1.2.0 (2026-11-24)

- **Talk on iPhone without Shizuku.** Pairline Bridge now starts its own small helper through Android's Wireless
  debugging: no extra app, no root. Set it up once in Settings › Talk on iPhone: turn on Developer options and Wireless
  debugging, tap Pair, and type the 6-digit code into Pairline's notification. After a restart, just turn Wireless
  debugging back on; Pairline starts the helper by itself. You can turn Wireless debugging off again once it runs (for
  banking apps). Android 10 starts it with one command from a computer.
- **If you used Shizuku with 1.1:** open Talk on iPhone and pair once; Shizuku is no longer needed.
- **Hotspot:** Pairline's hotspot only links your two phones and has no internet, which is why the iPhone says "No
  Internet Connection". For internet on the iPhone, use Android's own hotspot (new row in Settings); Pairline works
  over it too.

## 1.1.1 (2026-11-24)

- **Talk on iPhone is on the home screen.** "What your iPhone can do" now has a Talk on iPhone row with a Set up
  button, so it's easy to find. The keypad, mute and speaker row points to it too, instead of saying phones don't allow
  it. After a restart, the row says what's missing.

## 1.1.0 (2026-11-24)

- **Wi-Fi link.** When both phones are on the same Wi-Fi (or this phone's hotspot), the link also runs over Wi-Fi:
  faster syncs, files both ways and call audio. Bluetooth stays the always-on connection.
- **Talk on iPhone** (with Shizuku): hear and talk on the iPhone during a call, not just answer it.
- **Internet sharing:** the iPhone can use this phone's mobile data through a proxy on the local link, with an option
  to send `.ir` sites directly.
- **Send to iPhone** from Android's share menu, and files from the iPhone arrive here.
- **Delete and block from the iPhone:** deleting conversations, messages and calls, and a block list this phone
  enforces (texts not relayed, calls declined).
- **Update check:** once a day Pairline checks GitHub for a new version (you can turn it off). Every download is
  checked against the release's SHA-256 and signing certificate, and Android asks you before installing.
- Links to the Telegram news channel and help group in Settings › Support.

## 1.0.2 (2026-11-24)

- **Bluetooth fixes from real phones.** No more crash right after pairing on Android 13 and later, no reconnect loop on
  some older phones (such as the Galaxy Note 8), and crashes are now kept in the local log that you can share
  yourself.

## 1.0.1 (2026-11-24)

- **Runs on Android 9 and later** (it needed Android 11 before).

## 1.0.0 (2026-11-24)

- **First release.** Pair with the Pairline iPhone app once by scanning a QR code, then this phone relays SMS, one-time
  codes, calls, contacts and the call log to the iPhone over an end-to-end encrypted Bluetooth link. Nothing goes
  through a server.
- Setup guide, home screen with the link's state, both SIMs, diagnostics you can read and share yourself, and help.
- English, Persian, Arabic and Turkish.
