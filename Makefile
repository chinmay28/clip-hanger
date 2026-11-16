.PHONY: build build-web build-go icons icons-check version release test test-go test-web test-cover android clean dev

# Build everything: frontend + Go binary
build: build-web build-go

# Build React frontend
build-web:
	cd web && npm ci && npm run build

# Redraw the home-screen icon PNGs from web/public/icon.svg. The PNGs are
# committed, so run this whenever the SVG changes and commit the result.
icons:
	node scripts/make-icons.mjs

# Fail if a committed PNG no longer matches the SVG it is drawn from.
icons-check:
	node scripts/make-icons.mjs --check

# Build Go binary (static, no CGO)
# On Windows the output is clip.exe; on Unix it is clip.
ifeq ($(OS),Windows_NT)
BUILD_OUT := clip.exe
else
BUILD_OUT := clip
endif

# The year, month and patch the binary reports all come from git, which only
# exists at build time — scripts/version.mjs turns them into the -X flags that
# stamp them in (see internal/version). With no node to ask, the flags come out
# empty and the build calls itself v0.0.0: an unstamped development build.
VERSION_FLAGS := $(shell node scripts/version.mjs --ldflags 2>/dev/null)

build-go:
	CGO_ENABLED=0 go build -trimpath \
		-ldflags="-s -w $(VERSION_FLAGS)" \
		-o $(BUILD_OUT) ./cmd/clip

# Print the version this tree would build as
version:
	@node scripts/version.mjs

# Cross-compile release binaries for all platforms (requires Linux/macOS host)
release: build-web
	./scripts/build-release.sh

# Run every test: the Go packages and the web client's
test: test-go test-web

test-go:
	go test -count=1 ./...

# The web client's own tests (vitest) — among them, that the Android app's
# native-player hook does nothing in a plain browser.
test-web:
	cd web && { [ -d node_modules ] || npm ci; } && npm test

# The Android app (apps/android): its JVM tests, lint and a release APK,
# stamped with this tree's version. Needs a JDK 17+ and the Android SDK.
ANDROID_VERSION_FLAGS = -PappVersionName=$(shell node scripts/version.mjs 2>/dev/null || echo dev) \
	-PappVersionCode=$(shell node scripts/version.mjs --patch 2>/dev/null || echo 1)
android:
	cd apps/android && ./gradlew --no-daemon :core:test :app:lintDebug :app:assembleRelease $(ANDROID_VERSION_FLAGS)

# Run Go tests with coverage report
test-cover:
	go test -count=1 -coverprofile=coverage.out ./...
	go tool cover -html=coverage.out -o coverage.html

# Clean build artifacts
clean:
	rm -f clip clip.exe coverage.out coverage.html
	rm -rf internal/server/dist
	rm -rf web/node_modules web/dist

# Quick development (build Go only, assumes web is already built)
dev: build-go
	./$(BUILD_OUT) serve --port 8124
