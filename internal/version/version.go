// Package version carries the application version.
//
// The scheme is calendar-based: vYEAR.MONTH.PATCH, where the leading numbers
// are the year and month the code was committed and the patch number is the
// repository's commit count — every commit is a patch release, so `v2026.9.42`
// is the 42nd commit, made in September 2026. The month is written as a plain
// number, not zero-padded: that keeps the string valid semver, which forbids a
// leading zero, and nothing here orders versions by sorting text.
//
// None of the three can be known by a compiled binary: they all come from git,
// which is only there at build time, so all three are stamped at link time.
// The release line follows the calendar on its own — it is the commit date of
// HEAD, not the build clock, so it moves forward as work lands and a rebuild of
// an old tree still reports the version that tree originally shipped:
//
//	go build -ldflags "$(node scripts/version.mjs --ldflags)"
//
// `make build` (and `build-go`) does this for you via scripts/version.mjs,
// which is the one place the number is assembled and what the web client's
// build reads it from too.
package version

// Year, Month and Patch are stamped at link time (see the package comment).
// A bare `go build` leaves them at "0", so an unstamped development build
// calls itself v0.0.0 — never a plausible release.
var (
	Year  = "0"
	Month = "0"
	Patch = "0"
)

// String renders the full version, `v`-prefixed to match how the project tags
// releases (v2026.9.42). This is the one rendering — it's what the CLI prints,
// what /api/health reports, and what the web client shows in its header.
func String() string {
	return "v" + Year + "." + Month + "." + Patch
}
