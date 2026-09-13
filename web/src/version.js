/**
 * The running client's version, `vYEAR.MONTH.PATCH`, where the leading numbers
 * are the year and month HEAD was committed and the patch number is the
 * repository's commit count (so `v2026.9.42` is the 42nd commit, made in
 * September 2026).
 *
 * Inlined at build time by Vite's `define` (see vite.config.js) from
 * scripts/version.mjs — the same source the Go binary is stamped from, so the
 * header and `/api/health` always agree. Patch `0` means a build made without
 * git available.
 */
export const APP_VERSION = __APP_VERSION__
