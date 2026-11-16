import { describe, expect, it, vi } from 'vitest'
import { appSettings, canPlay, nativeApp, nativePlaylist, playAttempts, playNatively } from './nativeApp'

const clip = (over) => ({
  source: '/mnt/nvr',
  path: 'front/2026-11-16/12.00.00.dav',
  name: '12.00.00.dav',
  channel: 'N843A8_ch3',
  size: 1000,
  start_time: '2026-11-16T12:00:00-08:00',
  playable: false,
  remuxable: true,
  ...over,
})

// The hour as the browser holds it: newest first.
const hour = [
  clip({ path: 'a/12.40.dav', name: '12.40.dav', start_time: '2026-11-16T12:40:00-08:00' }),
  clip({ path: 'a/12.20.mp4', name: '12.20.mp4', start_time: '2026-11-16T12:20:00-08:00', playable: true }),
  clip({ path: 'a/12.10.avi', name: '12.10.avi', start_time: '2026-11-16T12:10:00-08:00', remuxable: false }),
  clip({ path: 'a/12.00.dav', name: '12.00.dav', start_time: '2026-11-16T12:00:00-08:00' }),
]

describe('in a plain browser', () => {
  it('finds no app', () => {
    expect(nativeApp({})).toBeNull()
    expect(nativeApp(undefined)).toBeNull()
    expect(nativeApp({ cliphangerApp: 'nope' })).toBeNull()
  })

  it('declines to play, so the page opens its own player', () => {
    const onFail = vi.fn()
    expect(playNatively(hour[0], { neighbours: hour, onFail, app: null })).toBe(false)
    expect(onFail).not.toHaveBeenCalled()
  })

  it('offers no app settings button', () => {
    expect(appSettings(null)).toBeNull()
  })

  it('keeps the web player ladder exactly as it was', () => {
    const mp4 = clip({ path: 'x.mp4', playable: true, remuxable: false })
    expect(playAttempts(mp4)).toEqual([
      '/api/clip?source=%2Fmnt%2Fnvr&path=x.mp4',
      '/api/clip/play?source=%2Fmnt%2Fnvr&path=x.mp4',
      '/api/clip/play?source=%2Fmnt%2Fnvr&path=x.mp4&transcode=1',
    ])
    expect(playAttempts(clip({ path: 'x.dav' }))).toEqual([
      '/api/clip/play?source=%2Fmnt%2Fnvr&path=x.dav',
      '/api/clip/play?source=%2Fmnt%2Fnvr&path=x.dav&transcode=1',
    ])
    expect(playAttempts(clip({ path: 'x.avi', remuxable: false }))).toEqual([
      '/api/clip?source=%2Fmnt%2Fnvr&path=x.avi',
    ])
  })
})

describe('inside the Android app', () => {
  it('finds the bridge object', () => {
    const app = { play() {} }
    expect(nativeApp({ cliphangerApp: app })).toBe(app)
  })

  it('hands the clip and its playable neighbours over, oldest first', () => {
    const app = { play: vi.fn(() => Promise.resolve()) }
    expect(playNatively(hour[1], { neighbours: hour, describe: () => 'Front door', app })).toBe(true)
    expect(app.play).toHaveBeenCalledTimes(1)

    const sent = app.play.mock.calls[0][0]
    // The .avi nothing can play is not in the playlist; the rest go in the
    // order they were recorded, so "next" is later footage.
    expect(sent.clips.map((c) => c.name)).toEqual(['12.00.dav', '12.20.mp4', '12.40.dav'])
    expect(sent.index).toBe(1)
    expect(sent.clips[1]).toEqual({
      name: '12.20.mp4',
      title: 'Front door',
      start: '2026-11-16T12:20:00-08:00',
      size: 1000,
      urls: [
        '/api/clip?source=%2Fmnt%2Fnvr&path=a%2F12.20.mp4',
        '/api/clip/play?source=%2Fmnt%2Fnvr&path=a%2F12.20.mp4',
        '/api/clip/play?source=%2Fmnt%2Fnvr&path=a%2F12.20.mp4&transcode=1',
      ],
      download: '/api/clip?source=%2Fmnt%2Fnvr&path=a%2F12.20.mp4',
    })
  })

  it('plays a clip alone when it comes without its hour', () => {
    const app = { play: vi.fn() }
    playNatively(hour[0], { app })
    const sent = app.play.mock.calls[0][0]
    expect(sent.index).toBe(0)
    expect(sent.clips).toHaveLength(1)
    expect(sent.clips[0].title).toBe('')
  })

  it('leaves a clip with no Play button to the page', () => {
    const app = { play: vi.fn() }
    expect(playNatively(hour[2], { neighbours: hour, app })).toBe(false)
    expect(app.play).not.toHaveBeenCalled()
  })

  it('falls back to the page player when the app refuses', async () => {
    const onFail = vi.fn()
    const app = { play: vi.fn(() => Promise.reject(new Error('no player'))) }
    expect(playNatively(hour[0], { neighbours: hour, onFail, app })).toBe(true)
    await Promise.resolve()
    await Promise.resolve()
    expect(onFail).toHaveBeenCalledTimes(1)

    const thrower = { play: () => { throw new Error('boom') } }
    const onFail2 = vi.fn()
    expect(playNatively(hour[0], { onFail: onFail2, app: thrower })).toBe(true)
    expect(onFail2).toHaveBeenCalledTimes(1)
  })

  it('treats an app without a player like a browser', () => {
    expect(playNatively(hour[0], { app: { openSettings() {} } })).toBe(false)
  })

  it('opens the app settings', () => {
    const app = { openSettings: vi.fn() }
    appSettings(app)()
    expect(app.openSettings).toHaveBeenCalled()
  })
})

describe('nativePlaylist', () => {
  it('keeps the chosen clip even when the neighbours omit it', () => {
    const lone = clip({ path: 'b/13.00.dav', start_time: '2026-11-16T13:00:00-08:00' })
    const p = nativePlaylist(lone, hour)
    expect(p.clips[p.index].urls[0]).toContain('b%2F13.00.dav')
    expect(p.index).toBe(p.clips.length - 1)
  })

  it('canPlay matches the Play button', () => {
    expect(canPlay(hour[0])).toBe(true)
    expect(canPlay(hour[2])).toBe(false)
    expect(canPlay(null)).toBe(false)
  })
})
