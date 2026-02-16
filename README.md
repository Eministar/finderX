# FinderX

FinderX is a modern, high-performance Windows file explorer built with Java 21 and JavaFX.  
It is optimized for instant search, responsive navigation, and a clean desktop-native experience.

## Highlights

- Instant filename search with asynchronous, cancellable query execution
- Persistent index cache for fast startup and low-latency lookup
- Real Windows file/folder icons
- Multi-drive selection and quick search-focused workflow
- Smart ranking (exact/prefix/substring + usage signal)
- Custom polished UI with rounded, dark clean styling
- Optional Discord Rich Presence (enabled by default, configurable in Settings)

## Platform Support

- Windows 10 / 11 (x64)

## Runtime Requirements

- Java 21+

## Installation

Use the latest signed installer from Releases (`FinderX-Setup-<version>.exe`) and complete setup.

## Packaging

The repository includes automated Windows packaging for:

- App image (`dist\FinderX\`)
- Installer (`dist\FinderX-Setup-<version>.exe`)
- Plugin API jar (`dist\FinderX-Plugin-API.jar`)

Version metadata is sourced from `pom.xml`, and signing is supported in the build pipeline.

### One-command release build (PowerShell)

```powershell
pwsh -File scripts/build-release.ps1
```

Useful flags:

- `-NoSign` disable code signing
- `-SkipApi` skip Plugin API jar build
- `-SkipInstaller` build only API jar

## Updates

FinderX includes an asynchronous update-check mechanism via GitHub Releases API integration.

## Configuration Storage

Application state and cache are persisted under:

- `%USERPROFILE%\.finderx\`

This includes index cache, pinned/recent items, and UI/performance preferences.

## Documentation

- Contributing: `CONTRIBUTING.md`
- License: `LICENSE`

## License

MIT License
