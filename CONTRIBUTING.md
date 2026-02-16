# Contributing — How to help 🧑‍💻

Thanks for your interest in contributing to FinderX! This document explains how to set up a local environment, run tests, and submit high-quality pull requests.

## Getting started

1. Ensure you have JDK 21+ and Maven installed.
2. Fork the repository and create a new branch (e.g. `feature/my-feature`).
3. Implement your changes and add tests where applicable.

## Local verification

```powershell
# Build
mvn clean package
# Run tests
mvn -DskipTests=false test
```

## PR checklist

Before opening a PR, please ensure:

- [ ] Your branch is up to date with `main`.
- [ ] The project builds locally and tests pass.
- [ ] You added or updated tests for changed behavior.
- [ ] Code follows the existing style and is well-documented.
- [ ] No credentials, secrets, or personal data are included in commits.

## Commit message style

Use short, imperative commit messages. Examples:

- `feat(search): faster prefix matching`
- `fix(ui): avoid blocking JavaFX thread`
- `build(release): add jpackage script`

## Plugin development

- Read `docs/plugin-api.md` for lifecycle and contracts.
- Use `examples/recent-files-plugin` as a template.
- Keep plugin dependencies minimal and avoid leaking sensitive data.

## Security reporting

If you discover a security vulnerability, please report it privately. Create an issue and mark it with `security` or contact the maintainers directly (see repository settings).

Thanks for contributing — we appreciate your time and effort! 🎉
