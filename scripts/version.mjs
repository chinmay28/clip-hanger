#!/usr/bin/env node
/**
 * The one place the app's version number is assembled.
 *
 * Scheme: vYEAR.MONTH.PATCH — a calendar version, where PATCH is the
 * repository's commit count, so `v2026.9.42` is the 42nd commit, made in
 * September 2026. The month is not zero-padded; that keeps the string valid
 * semver.
 *
 * All three numbers come from git, which only exists at build time: the Go
 * binary gets them stamped in via -ldflags, the web bundle gets the assembled
 * string inlined by Vite. Both call this file, so they can never disagree.
 *
 *   - YEAR/MONTH are the commit date of HEAD, so the release line follows the
 *     calendar with no constant to bump by hand. Deliberately the commit clock
 *     rather than the build clock: the same tree builds to the same version
 *     whenever it is built, and an old checkout still reports what it shipped.
 *   - PATCH is `git rev-list --count HEAD`.
 *
 * Usage:
 *   node scripts/version.mjs            # print e.g. v2026.9.42
 *   node scripts/version.mjs --patch    # print just the commit count (42)
 *   node scripts/version.mjs --ldflags  # print the -X flags that stamp a build
 *   import { appVersion } from './scripts/version.mjs'
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** The Go package whose variables a build stamps. */
const VERSION_PKG = 'github.com/chinmay28/clip-hanger/internal/version';

/** Run git in the repo root; null if it fails (no repo, no git, old git). */
function git(args) {
  try {
    return execFileSync('git', args, {
      cwd: repoRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
  } catch {
    return null;
  }
}

/**
 * The year and month of the release line: when HEAD was committed.
 *
 * The build clock would be the easy answer and the wrong one — it would move
 * the version of an unchanged tree from one month to the next, so two people
 * building the same commit would report different versions and a rebuilt
 * release would stop matching its tag. HEAD's commit date says the same thing
 * about *now* (work landing this month ships this month's line) while staying
 * a property of the code rather than of the machine that compiled it.
 *
 * Falls back to the build clock only when there is no git to ask, which is
 * already an unstamped build — see commitCount().
 */
export function releaseLine() {
  const committed = git(['log', '-1', '--format=%cd', '--date=format:%Y-%m']);
  const parsed = /^(\d{4})-(\d{2})$/.exec(committed ?? '');
  if (!parsed) {
    const now = new Date();
    return { year: now.getFullYear(), month: now.getMonth() + 1 };
  }
  // Number() drops the zero padding git's %m writes: semver forbids a leading
  // zero, so `2026.9` is the only spelling of September that is a legal tag.
  return { year: Number(parsed[1]), month: Number(parsed[2]) };
}

/**
 * The commit count on HEAD, or '0' when it can't be known — no repo (a tarball,
 * or a `COPY` that skipped `.git`), no git, or a **shallow** clone.
 *
 * Shallow is the trap, and it's why this isn't a bare `rev-list`: a clone made
 * with `--depth 1` answers `rev-list --count HEAD` with `1`, which is not an
 * error and not obviously wrong — it just quietly ships a build calling itself
 * `2026.9.1`. Refuse it. Patch 0 is the agreed "unstamped build" marker (it
 * matches the Go default), and a version ending in `.0` is visibly a
 * non-release rather than a plausible lie.
 *
 * Anything building a release therefore needs the full commit graph:
 * `fetch-depth: 0` on GitHub Actions, `--filter=blob:none` rather than
 * `--depth 1` for a cheap clone that still carries all of it.
 */
export function commitCount() {
  if (git(['rev-parse', '--is-shallow-repository']) === 'true') {
    process.emitWarning(
      'shallow git clone — the commit count is not the real one, reporting patch 0. ' +
        'Clone with --filter=blob:none (or fetch --unshallow) for a real version.',
    );
    return '0';
  }
  // A failed probe (git older than 2.15, or no repo at all) is not proof of
  // shallowness — fall through and let the count itself answer.
  return git(['rev-list', '--count', 'HEAD']) ?? '0';
}

/**
 * The full version string, `v`-prefixed to match how the project tags releases
 * (v2026.9.42). Must stay byte-identical to version.String() in the Go package.
 */
export function appVersion() {
  const { year, month } = releaseLine();
  return `v${year}.${month}.${commitCount()}`;
}

/**
 * The `-X` flags that stamp the Go binary. Every build site takes them from
 * here rather than spelling out the package path and three variables of its
 * own, so a build can't stamp two of the three and leave the last at 0.
 */
export function ldflags() {
  const { year, month } = releaseLine();
  return [
    `-X ${VERSION_PKG}.Year=${year}`,
    `-X ${VERSION_PKG}.Month=${month}`,
    `-X ${VERSION_PKG}.Patch=${commitCount()}`,
  ].join(' ');
}

// Invoked directly (by the build scripts), print rather than export.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const mode = process.argv.find((a) => a.startsWith('--'));
  const render = { '--patch': commitCount, '--ldflags': ldflags }[mode] ?? appVersion;
  process.stdout.write(render() + '\n');
}
