# Clip Hanger for Android

The Android app is your Clip Hanger server's own web interface, in an app,
with the clips played by the phone's own video player. How it is built is in
[`apps/android/README.md`](../apps/android/README.md).

## It is a client, not a server

Clip Hanger has to run on the machine your cameras record to — the Raspberry
Pi, NAS or desktop that holds the footage directory and the disks it fills.
A phone has neither, so the app does not run Clip Hanger itself: it connects
to the server you already have, the way a browser does. Install the server
first ([Quick start](../README.md#quick-start-on-linux-ubuntu--raspberry-pi)).

## Install

Download **`clip-hanger.apk`** on the phone and open it:

<https://github.com/chinmay28/clip-hanger/releases/latest/download/clip-hanger.apk>

Android asks once to allow installs from the browser you used. Android 10 or
newer. A new release installs over the old one, keeping its settings.
Every release is signed with the same key, so a new one installs over the old.
Releases from before that key (signed with a throwaway debug key) are the one
exception: uninstall such a build once before installing a newer one.

## First run

The app asks for the server's address:

- **Find on this network** lists the Clip Hanger servers that answer on this
  Wi-Fi; tap one. The phone needs to be on the same network, and the server
  must be a version that answers (see below).
- Or type it. `192.168.1.20` or `nvr.local` means `http://…:8124`, the port
  `clip serve` uses unless told otherwise; write `https://clips.example.com`
  for a server behind TLS.

**Continue** opens the server's page. The version under the button is the
app's own; tap the round badge at the bottom for who made it.

To change server later: long-press the app icon → **Settings**, or **⚙** in
the page's header, or **App settings** on the screen shown when the server
cannot be reached.

## Playing clips

Tap **▶ Play** on any clip. Inside the app it opens in the native player
rather than the page's:

- the phone's hardware decoders, and scrubbing that does not stall;
- a landscape clip turns the picture landscape, full screen;
- **⏮ / ⏭** go to the previous and next recording of the same hour, and when
  a clip ends the next one starts, so an hour of short segments plays through;
- **Save** puts the clip in *Downloads/Clip Hanger*; **Share** sends it to
  another app (with *Save to Downloads* first in the list);
- **✕** or Back returns to the page.

`.dav` plays the same way it does in a browser — through the server's ffmpeg,
which repackages it into an MP4. `.mkv`, `.ts`, `.flv` and `.avi`, which a
browser cannot open, the phone tries to play directly first, and asks the
server to convert them only if that fails. A clip that will not play even
converted says so, with Save beside it; VLC plays anything a camera wrote.

The page's own **↓ Download** links also save to *Downloads/Clip Hanger*.

## Finding servers on the network

The server answers the app's broadcast on **UDP port 8125**. That needs:

- a server from this release on;
- the server bound to the network (the default, `--bind 0.0.0.0`), not to
  `127.0.0.1`;
- UDP 8125 open in the server's firewall, if it has one, beside TCP 8124.

Turn it off with `clip serve --discovery=false` or `CLIP_DISCOVERY=off`;
`--discovery-port` moves it. The answer is the server's port, hostname and
version — nothing the web port does not tell anyone who can reach it — and
it is sent only to private, link-local and Tailscale addresses.

## Security

The app shows only the server you configured: other links open in the
phone's browser, and the native player and downloads accept only addresses on
that server. Plain `http` is allowed because that is how Clip Hanger runs on a
home network; certificates from a CA you installed on the phone are trusted,
for a server behind TLS at home. Clip Hanger has no sign-in of its own — the
README's warning about who can reach the port applies to the app exactly as to
a browser. If you put a signing-in reverse proxy in front, sign in on the
page; the player and downloads carry the same cookies.
