# Changelog

Releases are `vYEAR.MONTH.PATCH` — a calendar version, where the leading
numbers are the month `HEAD` was committed in (its committer date, in the
committer's own timezone) and the patch number is the repository's commit
count, so `v2026.9.42` is the 42nd commit, made in September 2026. Nobody
bumps anything: [`scripts/version.mjs`](./scripts/version.mjs) reads all three
from git, and every build — the binary, the web bundle, the Android app —
is stamped from it.

Each section below is the body of the corresponding GitHub release, and the
heading is the gate: [`.github/workflows/release.yml`](./.github/workflows/release.yml)
publishes a commit only when this file has a `## <version>` section naming
exactly the version that commit builds. A push to `main` without one is an
ordinary commit and is skipped; a tag or a manual run without one fails. So
cutting a release is renaming `## Unreleased` to the version the release
commit will build (`make version` on the commit before it, plus one) and
pushing it. `Unreleased` never matches a version, so it can never publish by
accident.

## Unreleased

**Downloads:** the server binaries as before (`clip-<version>-linux-amd64`,
`-linux-arm64`, `-darwin-*`, `-windows-amd64.exe`), and the Android app,
**`clip-hanger.apk`** — always at
<https://github.com/chinmay28/clip-hanger/releases/latest/download/clip-hanger.apk>.
Every asset is in `SHA256SUMS`.

### Clip Hanger for Android

A phone app for your Clip Hanger server. It is the server's own web
interface — channels, days, hours, labels, sources, quotas — in an app with
its own icon, and it plays clips the way a phone should:

- **A native player.** Play hands the clip to Media3 (the player SAND TV
  uses) instead of the page's `<video>`: the phone's hardware decoders,
  smooth scrubbing, landscape full screen for a landscape clip. **Previous**
  and **next** step through the rest of that hour's recordings, and at the
  end of one clip the next one starts, so an hour of short segments plays as
  the hour it was. Each clip climbs the same ladder the page does — the file
  as-is, the server's remux, its H.264 transcode — with one rung more:
  `.mkv`, `.ts`, `.flv` and `.avi`, which no browser plays but the phone
  does, go straight to the hardware decoders first, sparing the server the
  ffmpeg run.
- **Save and Share** a clip from the player: into *Downloads/Clip Hanger*,
  or the share sheet (with *Save to Downloads* first in it). The page's own
  Download links save there too.
- **Find on this network.** The first screen looks for servers on the Wi-Fi
  and lists what answers; or type the address (`192.168.1.20` means port
  8124).
- **A thin client, deliberately.** The footage lives on the machine your
  cameras record to, and Clip Hanger runs there beside it. The app is a
  window onto that server, not a second copy of it.

In a browser nothing changes: the page offers the native player only when it
finds itself inside the app (`window.cliphangerApp`), and its tests say so.

See [the guide](./docs/android.md).

### The server answers "Find on this network"

`clip serve` answers the app's discovery broadcast on **UDP 8125** (the port
after the web server's) with its port, hostname and version — nothing the web
port does not already say to anyone who can reach it, and only to private,
link-local and Tailscale addresses. It stays off when the server is bound to
loopback (nobody who heard it could reach the address), and
`--discovery=false` or `CLIP_DISCOVERY=off` turns it off;
`--discovery-port` moves it.

### `version.mjs --date`

`node scripts/version.mjs --date` prints the release line as `YEAR-MM`, for
builds that want it on its own. The month is now read from `%cI` — the
committer date in the committer's timezone, which is what it was already;
only the spelling changed.

### Uninstall is the install command with a flag

`curl -fsSL …/quickstart.sh | sudo bash -s -- --uninstall` stops the service
and removes its unit and `/opt/clip`, keeping the quota config, its backups and
every clips directory, then prints how to delete what it kept. It is safe to
run twice, and it installs, clones and downloads nothing on the way.

### The release line follows the calendar on its own

`YEAR.MONTH` used to be a pair of constants in the Go source, bumped by hand
when a release line opened — and, as constants do, they sat at `2026.8` well
into September. They are now read from the commit date of `HEAD`, so the line
moves with the work and there is nothing left to forget.

It is the commit clock, not the build clock: the same tree still builds to the
same version whenever it is built, two machines building one commit still
agree, and a rebuilt release still matches its tag. All three numbers now come
from git and are stamped at link time, which `scripts/version.mjs --ldflags`
assembles for every build site at once — so a build can no longer stamp two of
the three. A binary built with none of it calls itself `v0.0.0`.

### Clip Manager is now Clip Hanger

New name, new mark: clips hang from a rail under a hook that doubles as a play
button. Everything that carries the name moves with it — the wordmark and the
home-screen icons, the docs, and the Go module path, now
`github.com/chinmay28/clip-hanger`. The repository moved to match, so `go get`
and the quick-start URL both want the new path.

Nothing an installed machine depends on changes: the binary is still `clip`,
and the systemd unit, the `/opt/clip` and `/var/lib/clip` paths and every
`CLIP_*` environment variable are untouched — an existing install upgrades in
place.

### The first load stopped waiting on the walk

Every listing used to walk every source before answering — and one app load
fires several listings, so opening the app meant walking the archive a few
times over, slower with every file the cameras add. Listings are now
answered from a **scan cache**: the last walk's answer, served immediately,
with a fresh walk running behind it when the snapshot is older than a few
seconds. The response says which it got (`"stale": true`), and the app
quietly re-asks until the fresh walk lands — so the footage appears at once
and corrects itself moments later if anything changed. The snapshot persists
in the data directory (`scancache.json.gz`), so even a freshly restarted
server answers its first request from the previous run's walk, and it is
warmed at startup besides. The walk is still the only truth: quota
enforcement never reads the cache — footage is deleted only off a fresh walk
— and a cleanup drops the snapshot so no listing shows clips that are gone.

### Quotas moved from directories to channels

Per-camera quotas used to key on the first directory under a source — which,
for recorders that bucket FTP uploads by date, made the Storage table offer a
quota per *date* and nothing per camera. Quotas now key on the **channel** —
the identity the clips themselves carry (`N843A8_ch3_main_…` → `N843A8_ch3`,
camera directory as the fallback), the same thing the Clips tab groups by and
labels rename. So "Front door keeps 10 GB, the backyard 5" works on every
layout, and a channel's line follows its footage across date directories and
sources alike. Channel labels name the rows in the Storage table; the quota
stays drawn on the channel underneath, so renaming a channel never moves a
line. Old configs are migrated on load: `camera_quota_bytes` folds into
`channel_quota_bytes` (for one-directory-per-camera layouts the directory
name *is* the channel, so each line lands where it always was), and
enforcement reports now say `channel quota` where they said `camera`.

### Channels, days, hours — and a clean front page

The home page is now the clips and nothing else — the *Sources* and *Storage*
panels moved to a **Settings** tab. The flat file list is gone, replaced by a
drill-down built for cameras that write hundreds of clips a day: **channel**,
then **day**, then **hour**. Channels are read from the recordings' own names
(`N843A8_ch3_main_…` → channel `N843A8_ch3`, with the camera directory as the
fallback) and can be **labeled** — select a chip, rename, and "N843A8 ch3"
becomes "Front door" in every browser, stored in the server's config. Days
sit in a horizontally scrollable strip (*Today*, *Yest.*, dates), each stop
carrying its clip count; the chosen day's clips group into collapsible
**hour sections** ("9 PM · 14 clips · 68 MB") with only the latest hour open
on a busy day. Each row is led by the time the recording **started**, parsed
from the filename's timestamp rather than the upload's mtime.

The browsing is as light as it looks: a new `GET /api/summary` carries just
the counts the menus are drawn from, and `GET /api/clips` takes `?day=` and
`?channel=` filters — the client fetches one day of one channel at a time,
so opening the app on a phone no longer downloads the whole archive's
listing.

### .dav plays in the browser

Formats a browser will not take directly — `.dav` above all — now play in
place: the server repackages the recording through the machine's own ffmpeg
into a cached, seekable `+faststart` MP4 served with range support — which is
what iOS Safari requires before it will play a remuxed stream at all (the
first cut of this feature streamed fragmented MP4, which desktop Chrome
accepted and iPhones refused). H.264 is container-copied; HEVC is copied and
tagged `hvc1`, the tag Safari insists on; codecs no browser decodes (MJPEG in
an old `.avi`) are transcoded to H.264, and the player falls back to a
server-side H.264 transcode automatically before it ever gives up on a clip.
Camera audio (usually G.711) is re-encoded to AAC so it survives the MP4.
Prepared MP4s are cached in the data directory (capped at 512 MB, oldest
evicted) so a clip is repackaged once, not once per view. The quickstart
installs ffmpeg by default (`INSTALL_FFMPEG=never` skips it); without one
those formats stay labelled downloads and the server says so at startup.

### One or more source directories

Clips can now come from **several source directories** at once — the NVR's
tree on the internal disk and the overflow on a USB drive, say. Sources named
on the command line (`--clips`, repeatable, or colon-separated
`CLIP_CLIPS_DIR`) are pinned; more can be added and removed in the app's new
*Sources* panel, which adopts existing directories and never creates or
deletes anything — removing a source only takes it out of view. Quotas span
the union: the total is measured across every source, enforcement retires the
oldest footage first wherever it sits, and a camera's name is its identity,
so the same camera split across two sources answers to one line. A source
that stops being readable (an unplugged drive) stays listed, is reported
rather than forgotten, drops out of the figures out loud, and is never
enforced against.

### The first cut

Clip Hanger exists: a single static Go binary with an embedded web client
(installable to a phone's home screen as a PWA) over a directory of
security-camera clips.

- **Browse and play.** The clips directory is listed fresh on every load —
  one subdirectory per camera, the way NVRs write it. Browser-playable
  formats (`.mp4`, `.webm`, `.mov`, `.m4v`) play in place with scrubbing;
  `.dav` and the other camera containers are offered as downloads, honestly
  labelled, until the app grows remuxing.
- **Quotas.** A directory-wide quota, and per-camera quotas over it.
  Enforcement retires the oldest footage first — camera quotas before the
  global one, so a noisy camera pays for itself before a quiet one loses
  anything — and only ever touches files it recognises as footage. Every run
  reports exactly what went and why; a dry run answers "what would go"
  without touching anything. Runs hourly under `serve`, on demand from the
  app, and from the shell as `clip prune`.
- **One-command install.** `scripts/quickstart.sh` sets it up as a hardened
  systemd service on Ubuntu / Debian / Raspberry Pi OS, upgrades in place,
  snapshots the quota config before every upgrade, and rolls back a build
  that fails its health check. It never writes into the clips directory.
