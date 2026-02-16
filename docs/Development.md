# Development — FinderX 🛠️

This guide helps contributors set up a local development environment and make meaningful contributions.

Requirements

- JDK 21+
- Maven 3.6+
- Recommended IDE: IntelliJ IDEA (Community or Ultimate)

Project layout (short)

- `src/main/java/` — application source
- `src/main/resources/` — icons, themes, language files
- `examples/` — example plugins (e.g. `recent-files-plugin`)
- `scripts/` — build and release scripts (PowerShell)
- `pom.xml` — Maven project descriptor

Build & test

```powershell
# Build
mvn clean package
# Run tests
mvn test
```

IDE setup (IntelliJ)

1. Open the project by selecting `pom.xml`.
2. Set Project SDK to JDK 21 (File → Project Structure).
3. Create a Run configuration pointing to the main application class if needed.

Debugging

Start FinderX with remote debugging enabled:

```powershell
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar target/FinderX-1.0.jar
```

Then attach your IDE to port 5005.

Code style & linting

- Follow standard Java conventions. Keep methods small and tests focused.
- Optionally add Checkstyle or Spotless to `pom.xml` for consistent formatting.

Contribution workflow

- Fork the repo → create a feature branch (e.g. `feature/xyz`) → implement changes → add tests → open a PR.
- Run a full build and test locally before opening a PR.

Plugin development (brief)

- Consult `docs/plugin-api.md` for API details and lifecycle.
- Use `examples/recent-files-plugin` as a starting point.
- Keep plugins isolated and avoid leaking sensitive data.

More

If you intend to add public APIs, document them in `docs/plugin-api.md` and consider adding generated Javadocs to the repository or CI artifacts.

— End —
