# Clip Hanger for Android — how it is built

Using it is in the [guide](../../docs/android.md). This is the design.

```
 ┌──────────────────────── the phone ───────────────────────────┐
 │  MainActivity                                                 │
 │   WebView ── the server's own web UI, from Origin only         │
 │     ▲  cliphanger-bridge.js (document start, Origin only)      │
 │     │  ClipHangerAndroid ⇄ PageBridge: play, fetch, settings   │
 │     │                         │                               │
 │     │                         ▼                               │
 │     │                  PlayerActivity (Media3 ExoPlayer)       │
 │     │                   Playlist + Ladder from :core           │
 └─────┼─────────────────────────┼───────────────────────────────┘
       ▼                         ▼
   http(s)://server:8124   /api/clip, /api/clip/play
```

## Thin client only

SAND Vault's app can run its server on the phone; this one deliberately
cannot. Clip Hanger's job is the directory the cameras record into and the
disk it fills, and those are on the NVR box, not in anybody's pocket. So the
APK carries no Go binary and no foreground service: one mode, one address.

## One interface

The web client already does everything — channels, days, hours, labels,
sources, quotas, cleanups. The app shows the server's page and adds only what
a web view lacks or does worse. Each origin has its own cookies and storage,
so switching servers never mixes them.

## The native player — the piece that pays

How the page plays a clip: `/api/clip` serves the recording's bytes with
range support (browser-ready `.mp4/.webm/.mov/.m4v`); `/api/clip/play`
repackages anything else — `.dav` above all — through the server's ffmpeg
into a cached, `+faststart` MP4 (H.264/HEVC copied, other codecs transcoded),
and `&transcode=1` forces H.264. The page's `<video>` climbs that ladder on
`error`.

Inside the app, `web/src/nativeApp.js` sees `window.cliphangerApp` and hands
Play to the bridge instead: the clip's hour as a playlist (oldest first), the
index of the clip, and each clip's ladder of relative URLs. In a browser the
object is absent and nothing changes — `nativeApp.test.js` pins both halves.

`PlayerActivity` plays it with Media3 (the same release SAND TV uses):

- `core/Playlist` parses the page's message **against the origin** — every
  address resolved onto it, anything off it dropped — sorts by recording
  time, and is the immutable next/previous state. The Media3 playlist mirrors
  it, so the controller's ⏮/⏭ and auto-advance at the end of a clip work as
  Media3 does them.
- `core/Ladder` is the page's ladder plus one rung: for `.mkv/.ts/.flv/.avi`,
  which Media3 extracts and browsers do not, the raw file goes first, to the
  hardware decoders, sparing the server the remux. A playback error steps one
  rung down (`replaceMediaItem`); past the last, the player says so with Save.
- `core/PlayerText` writes the title (channel label · start time, in the
  phone's zone and locale).
- Landscape video locks sensor-landscape; the bars hide; the web view's
  cookies go with each request, for a server behind a signing-in proxy.
- The playlist crosses to the activity in memory (an hour can outgrow an
  intent); a trimmed window of it (`Playlist.window`) is saved for process
  death.

## The page bridge

`assets/cliphanger-bridge.js` is added at document start, and the
`ClipHangerAndroid` message port exists, for the configured origin only
(`addDocumentStartJavaScript` + `addWebMessageListener` with that origin as
the only rule). Messages (`core/Bridge`):

| Page does | Bridge sends | App does |
|---|---|---|
| Play inside the app | `play` + playlist | opens the native player |
| clicks `<a download href="/api/clip…">` | `fetch` | downloads it with the web view's cookies into *Downloads/Clip Hanger* (MediaStore), redirects followed only within the origin |
| ⚙ App settings | `settings` | opens the settings screen |

SAND's chunked blob transfer and `navigator.share` shim are not ported: this
page hands over no `blob:` files and never calls `navigator.share`. Share is
the native player's button (`Sharing`: fetch to the cache, FileProvider, the
share sheet with *Save to Downloads* first).

## Discovery

The server had none. "Find on this network" is worth it here — the address is
a LAN IP nobody remembers, and typing it is the only setup step — so the
server gained [`internal/discover`](../../internal/discover): a UDP responder
on 8125 answering `CLIP-HANGER-DISCOVER/1` with `{service, name, port,
version}`, only to private/link-local/CGNAT senders, off on a loopback bind
and with `--discovery=false` / `CLIP_DISCOVERY=off`. `core/DiscoveryProtocol`
is the client half and `LanDiscovery` the broadcast; Go and Kotlin tests pin
the same constants.

## Navigation

`core/NavigationPolicy`: the origin's pages (and `blob:`/`data:`) stay in the
app; other `http(s)` opens the browser; `intent:` is launched with component
and selector stripped and `CATEGORY_BROWSABLE`; `javascript:`, `file:`,
`content:` and the like are refused. `core/Origin` reads the typed address
(no scheme → `http://`, no port → 8124).

## Icon, badge, version

- Icons: `scripts/make-icons.mjs` (the repo's existing dependency-free
  rasteriser of `web/public/icon.svg`) also draws `mipmap-*/ic_launcher.png`,
  the adaptive foreground (no plate, artwork inside the 66dp safe circle),
  `ic_launcher_background.xml` (the SVG plate's colour) and
  `drawable-nodpi/clip_logo.png`. `make icons-check` fails on drift. The app
  posts only the system download notification, so no silhouette icon is
  needed.
- The developer badge (`dev_badge.png`, `dev_badge_full.png`, copied from
  `web/public/`) sits at the bottom of the setup screen, 36dp in a 44dp
  target; `DevFlash` is the page's full-screen flash done natively (3 s veil,
  overshoot, tap dismisses). Settings → About → Developer throws it too.
- `versionName`/`versionCode` come from `-PappVersionName` /
  `-PappVersionCode` (the release workflow passes `scripts/version.mjs` and
  `--patch`); a local build is `dev` / 1. The version shows under Continue.

## Layout and commands

```
apps/android/
├── core/   plain Kotlin, JVM-tested: Origin, NavigationPolicy, AppConfig,
│           Bridge, Playlist, Ladder, PlayerText, DiscoveryProtocol,
│           FileNames, AcceptTypes
└── app/    MainActivity, PageBridge + assets/cliphanger-bridge.js,
            PlayerActivity, Downloads, Sharing, SaveToDownloadsActivity,
            SetupActivity, SettingsActivity, LanDiscovery, DevFlash
```

```bash
cd apps/android
./gradlew -PcoreOnly :core:test                  # no Android SDK needed
./gradlew :core:test :app:lintDebug :app:assembleRelease
make android                                     # same, from the root, version-stamped
```
