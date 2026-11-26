# The App Review video

App Review can't pair an Android phone, and demo mode shows calls on Pairline's own screen without audio. So the
system call screen (CallKit) and the `voip` and `audio` background modes are only visible in a video (D-097). Without
one, expect "Guideline 2.1 – Information Needed" or "2.5.4 – background modes".

Record it once on the real phones, upload it unlisted (YouTube or any link that plays in a browser without signing
in), and put the link in `metadata/review_information/notes.txt` in place of `VIDEO_LINK_HERE`. `upload.sh` refuses to
run until then. If App Review asks for a video on a version already in review, answer in App Store Connect's
Resolution Center with the same link: no new build needed.

## What to film (about 2 minutes, one take is fine)

Film the iPhone's screen (screen recording with the microphone on, or a second camera showing both phones).
Use a release build from TestFlight, not a debug build.

1. **Pairing.** Open Pairline on the iPhone, Pair your Android phone, Continue at the camera step, scan the code
   Pairline Bridge shows, then "You're paired!".
2. **A code arrives.** Send the Android number an SMS with a one-time code from another phone. Show the
   notification on the iPhone and the Copy button.
3. **A call on a locked iPhone.** Lock the iPhone and call the Android number from another phone. Show the
   **CallKit screen** on the locked iPhone, the caller's name or number, and answer.
4. **Call audio in the background (`audio`).** With Talk on iPhone set up, talk to the caller through the iPhone
   while it stays locked or another app is open, so the caller is heard from the iPhone. Hang up from the iPhone.
5. **Ring my iPhone (`audio`).** Put Pairline in the background, start Ring my iPhone from the Android app, and show
   the locked iPhone beeping; tap Found it.
6. **Background link (`bluetooth-central`).** Optional: a message arriving while Pairline is in the background.

Keep personal numbers and message text out of the frame, or use a test SIM and a second test phone.
