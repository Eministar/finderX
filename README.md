# FinderX 🚀

FinderX is a fast, lightweight file launcher and explorer for Windows, built with Java 21 and JavaFX. It helps developers and power users find and open files, projects, and resources instantly with a polished, native-feeling UI.

[![Build status](https://img.shields.io/badge/build-unknown-lightgrey)](https://github.com/your/repo/actions)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Why FinderX?

- Instant filename search with asynchronous, cancellable queries ⚡
- Persistent index cache for fast startups and low-latency lookups 🗂️
- Native Windows file icons and a modern, compact UI ✨
- Multi-drive support and smart ranking (prefix / substring / usage signals) 🔎
- Extensible plugin system for custom workflows 🧩

Platform & Requirements

- Platform: Windows 10 / 11 (x64)
- Java: JDK 21+
- Build: Maven

Quickstart (short)

1. Install JDK 21+ and Maven.
2. Build: `mvn clean package`
3. Run: `java -jar target/FinderX-1.0.jar` or use the official installer from Releases.

See `docs/Quickstart.md` for a detailed step-by-step guide. 🧭

Documentation

- Project overview: `docs/Overview.md`
- Quickstart: `docs/Quickstart.md`
- Development guide: `docs/Development.md`
- Plugin API: `docs/plugin-api.md` (existing) 📚
- Themes & styling: `docs/themes.md` (existing) 🎨
- Example plugin: `examples/recent-files-plugin` 🧪
- Contributing: `CONTRIBUTING.md`
- Code of Conduct: `CODE_OF_CONDUCT.md`

Building & Releases

This repository includes scripts to package a Windows installer and application bundles. A common release command (PowerShell) is:

```powershell
pwsh -File scripts/build-release.ps1
```

Flags: `-NoSign`, `-SkipApi`, `-SkipInstaller`

Security & Privacy

- Do not publish secrets or personal data in issues or PRs.
- If you find a security vulnerability, please report it privately (see `CONTRIBUTING.md`). ⚠️

Contributing & Support

- Report bugs and request features via GitHub Issues.
- Open Pull Requests with clear descriptions and tests when applicable.
- For plugin authors: check `docs/plugin-api.md` and `examples/recent-files-plugin`.

License

FinderX is distributed under the terms of the MIT License — see `LICENSE` for details.

—

© FinderX project
