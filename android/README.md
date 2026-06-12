# ZiVKord for Android - delete your Discord messages from your phone, safely.

> The desktop app, shrunk down to fit in your pocket. Same deal: you log into the
> real Discord, it never sees your token, and it does the boring deleting so you
> do not have to thumb through a thousand messages tapping "delete" like a caveman.

This is the Android sibling of [ZiVKord](../README.md). Same trust model, same
flat refusal to ask for your token, now driven by thumbs instead of a mouse.

It is not on the Play Store and never will be. Google does not love apps that
automate Discord, and honestly the whole point is that you sideload something whose
source you can actually read. So you grab the APK and install it yourself, like the
good old days.

## What it does

- You log into Discord (the real one, inside the app) - password or scan the QR,
  your call, your credentials.
- The login screen vanishes and a proper native control panel takes over. No more
  fighting with Discord's website squished onto a phone screen.
- You search your servers and DMs, tap one (for a server you then pick a channel,
  or just "all channels"), set whatever filters you feel like, and hit the button.
- It finds your messages and deletes them, slow and human-like so Discord does not
  decide a robot moved in.

Everything that matters happens on your device. The token gets lifted off a request
Discord already makes and stays inside the web view - never handed to the app,
never logged, never sent anywhere. The app is wired so it literally cannot reach any
server that is not Discord. Close it and it forgets you entirely, so next time you
log in fresh. Same paranoia as the desktop version, ported across.

## Install

1. Download `ZiVKord-x.y.z.apk` from the
   [Releases page](https://github.com/zivikzi/zivkord/releases/latest).
2. Open it. Android will get nervous about "installing from unknown sources" and
   make you flip a switch. That is just because it did not come from the Play Store.
3. Play Protect might mutter "app is unknown". Normal for anything sideloaded - tap
   install anyway. It asks for nothing scary, just internet access.
4. Log in, pick a target, scan, delete.

Want to check the download is the real thing? Hashes are on the release. Or build it
yourself, see below.

## What is in the control panel

- **Search** your servers and DMs. For a server, drill into one channel or wipe all
  of them.
- **Filters**: only messages containing some text (a real substring match, not
  Discord's flaky word-only search), only ones with links / files / embeds, a date
  range, or just the most recent N messages. No filters means everything you posted.
- **Pacing slider** from paranoid-slow to brisk, plus an optional longer break every
  so many deletes. Slower is safer.
- **Scan** counts what would go without deleting a thing. Always scan first - the app
  nags you about this in red, and it is not kidding.
- A progress bar and a clear "job complete" at the end so you are not left guessing.

While it runs it keeps the screen on and shows a notification, so the phone does not
doze off mid-job. One heads up: do not switch apps or lock the phone while it is
working. The session lives in memory, so leaving the app drops you back to the login
screen and stops the run. The app warns you about that too, in red, while it works.

## Build it yourself (the paranoid section)

You will need the Android SDK (platform 34 + build-tools 34) and a JDK 17. Point the
build at your SDK and go:

```
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug      # or assembleRelease for a release build
```

The whole thing is small enough that you can actually read it:

- `app/src/main/assets/zivkord-core.js` - the engine. Grabs the token off a request,
  reads your message history, deletes, and behaves around the rate limits. This is
  the file to read if you do not trust us.
- `app/src/main/java/.../MainActivity.java` - the native panel, the network lock
  (drops anything that is not Discord), and the forget-on-close session handling.
- `app/src/main/java/.../DeleteService.java` - the bit that keeps a long run alive.

No analytics, no telemetry, no third-party libraries at all. The network block sits
right there in `MainActivity` where you can see it.

A release build has to be signed to install. That is a normal self-signed key, not a
Play Store thing - any keystore works.

## Liked it?

There is a "Buy me a coffee?" button in the app that coughs up a Bitcoin address.
Zero pressure - it works exactly the same whether you send sats or not.

## License

MIT, same as the rest of ZiVKord. Do what you want.
