# Overview — FinderX 🧩

What is FinderX?

FinderX is a focused file launcher and lightweight explorer designed for speed and productivity. It provides a responsive search-first UI, a small persistent index for instant results, and plugin extensibility so teams can tailor behavior to their workflows.

Target audience

- Developers who switch between files and projects frequently
- Power users who want fast keyboard-driven access to files
- Teams that build plugins to integrate custom tools or workflows

High-level architecture

- Core: search, indexing, and UI (Java + JavaFX)
- Persistence: user settings and index cache
- Plugin system: runtime API to extend UI and actions (see `docs/plugin-api.md`)
- Packaging: Windows installer and jpackage artifacts

Key features

- Instant, cancellable search queries for snappy feedback ⚡
- Persistent index to minimize startup latency 🗂️
- Native file icons and a modern, accessible UI ✨
- Pluggable architecture for custom integrations 🧩
- Themeable UI (see `docs/themes.md`) 🎨

Where to go next

- Quickstart: `docs/Quickstart.md`
- Development: `docs/Development.md`
- Plugin API: `docs/plugin-api.md`
