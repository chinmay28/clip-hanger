/* Clip Hanger for Android — what the app adds to the server's page.

   Runs before the page's own scripts, on the configured server's origin only
   (see PageBridge.kt and core/Bridge.kt for the other side). It does three
   things a web view cannot do by itself:

   - defines window.cliphangerApp, which the page feature-detects
     (web/src/nativeApp.js): Play then hands the clip and the rest of its hour
     to the app's native player instead of the page's <video>, and the header
     offers the app's own settings. Without this object — in any browser — the
     page behaves exactly as it always has.
   - saving: an <a download> on the server's own address (the clip rows'
     Download, the player's Download) is fetched by the app into
     Downloads/Clip Hanger, with the page's cookies. A web view has no
     Downloads of its own.

   Nothing here runs on any other site. */
(function () {
  'use strict'
  if (window.cliphangerApp) return
  var port = window.ClipHangerAndroid
  if (!port || typeof port.postMessage !== 'function') return

  var nextRe = 1
  var waiting = {}

  port.addEventListener('message', function (event) {
    var reply
    try { reply = JSON.parse(event.data) } catch (e) { return }
    var w = waiting[reply.re]
    if (!w) return
    delete waiting[reply.re]
    if (reply.ok) w.resolve(reply)
    else w.reject(new Error(reply.error || 'The app could not do that.'))
  })

  function call(msg) {
    return new Promise(function (resolve, reject) {
      var re = nextRe++
      waiting[re] = { resolve: resolve, reject: reject }
      msg.re = re
      port.postMessage(JSON.stringify(msg))
    })
  }

  function warn(e) { console.warn('Clip Hanger app:', e && e.message ? e.message : e) }

  /* An <a download> on the server's own address: true when the app took it. */
  function takeAnchor(a) {
    if (!a || typeof a.hasAttribute !== 'function' || !a.hasAttribute('download')) return false
    var href = a.href || ''
    if (href.indexOf(location.origin + '/') !== 0) return false
    call({ t: 'fetch', url: href, name: a.getAttribute('download') || '' }).catch(warn)
    return true
  }

  var click = HTMLAnchorElement.prototype.click
  HTMLAnchorElement.prototype.click = function () {
    if (takeAnchor(this)) return
    return click.apply(this, arguments)
  }
  document.addEventListener('click', function (e) {
    if (e.defaultPrevented) return
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null
    if (a && takeAnchor(a)) e.preventDefault()
  }, true)

  window.cliphangerApp = Object.freeze({
    platform: 'android',
    version: "__APP_VERSION__",
    // playlist: { index, clips: [{ name, title, start, size, urls, download }] }
    // — see nativePlaylist() in web/src/nativeApp.js.
    play: function (playlist) { return call({ t: 'play', playlist: playlist }) },
    openSettings: function () { return call({ t: 'settings' }) },
  })
})()
