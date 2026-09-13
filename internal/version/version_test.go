package version

import "testing"

// The version string is a contract: the CLI prints it, /api/health reports it,
// the release filenames carry it, and a tag has to match it exactly. These pin
// the shape rather than any particular number, since the numbers arrive from
// the linker.
func TestString(t *testing.T) {
	for _, tc := range []struct {
		name               string
		year, month, patch string
		want               string
	}{
		{"unstamped", "0", "0", "0", "v0.0.0"},
		{"stamped", "2026", "9", "42", "v2026.9.42"},
		// Single-digit months are not padded: semver forbids a leading zero,
		// and every release is tagged with this string.
		{"single digit month", "2026", "1", "7", "v2026.1.7"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			defer restore(Year, Month, Patch)
			Year, Month, Patch = tc.year, tc.month, tc.patch

			if got := String(); got != tc.want {
				t.Errorf("String() = %q, want %q", got, tc.want)
			}
		})
	}
}

// The stamped values are package variables, so a test that sets them has to
// put them back.
func restore(year, month, patch string) func() {
	return func() { Year, Month, Patch = year, month, patch }
}
