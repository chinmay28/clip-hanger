/* What the page does differently inside the Clip Hanger Android app — and,
   just as much, the proof that it does nothing differently anywhere else.

   The app adds `window.cliphangerApp` to this page before any of the page's
   own scripts run (apps/android/app/src/main/assets/cliphanger-bridge.js),
   on the server's own origin only. Everything below feature-detects it: in a
   plain browser it is undefined, every function here answers "no", and the
   page plays clips in its own <video> exactly as before.

   Inside the app, Play hands the clip — with the rest of its hour, so the
   native player can step to the next and previous recording — to a Media3
   player: hardware decoding, smooth scrubbing, landscape full screen. */
import { clipURL, playURL } from './api'

/** The app's bridge object, or null in a browser. */
export const nativeApp = (w = globalThis.window) =>
  (w && w.cliphangerApp && typeof w.cliphangerApp === 'object') ? w.cliphangerApp : null

/** Whether a clip has a Play button at all: served as-is, or remuxed. */
export const canPlay = (clip) => Boolean(clip && (clip.playable || clip.remuxable))

/* Every URL worth trying for a clip, in order — the ladder the web player and
   the native one both climb. The file as-is (when a browser takes the
   container), then the server's remux, then its H.264 transcode, the one
   every decoder takes. A clip neither playable nor remuxable gets only its
   bytes. */
export function playAttempts(clip) {
  const list = []
  if (clip.playable) list.push(clipURL(clip))
  if (clip.playable || clip.remuxable) {
    list.push(playURL(clip))
    list.push(playURL(clip, true))
  }
  return list.length > 0 ? list : [clipURL(clip)]
}

const sameClip = (a, b) => a.source === b.source && a.path === b.path
const startMs = (clip) => {
  const t = Date.parse(clip.start_time || clip.mod_time || '')
  return Number.isNaN(t) ? 0 : t
}

/* The message the app's player takes: the playable clips of the hour in the
   order they were recorded (oldest first, so "next" is later footage), and
   which of them to start on. Addresses are relative to the page's origin —
   the app resolves them there and refuses anything that would leave it.

   describe(clip) names a clip for the player's title bar (the channel's
   label, say); the start time travels separately so the phone can render it
   in its own locale. */
export function nativePlaylist(clip, neighbours = [], describe = () => '') {
  const list = neighbours.filter(canPlay)
  if (!list.some((c) => sameClip(c, clip))) list.push(clip)
  list.sort((a, b) => startMs(a) - startMs(b))
  return {
    index: list.findIndex((c) => sameClip(c, clip)),
    clips: list.map((c) => ({
      name: c.name,
      title: describe(c) || '',
      start: c.start_time || c.mod_time || '',
      size: c.size || 0,
      urls: playAttempts(c),
      download: clipURL(c),
    })),
  }
}

/* Plays a clip in the app's native player, if there is one.

   Returns false — and does nothing at all — in a browser, which is the
   caller's cue to open its own player. Inside the app it returns true; if
   the app then refuses (an older app without a player, say), onFail runs so
   the page can fall back to its own player after all. */
export function playNatively(clip, { neighbours = [], describe, onFail, app = nativeApp() } = {}) {
  if (!app || typeof app.play !== 'function' || !canPlay(clip)) return false
  let pending
  try {
    pending = app.play(nativePlaylist(clip, neighbours, describe))
  } catch (e) {
    if (onFail) onFail(e)
    return true
  }
  if (pending && typeof pending.then === 'function') {
    pending.then(undefined, (e) => { if (onFail) onFail(e) })
  }
  return true
}

/** The app's own settings, when the page is inside it; null otherwise. */
export function appSettings(app = nativeApp()) {
  return app && typeof app.openSettings === 'function' ? () => app.openSettings() : null
}
