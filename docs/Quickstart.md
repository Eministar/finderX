# Quickstart — FinderX 🧭

Get started quickly with FinderX.

Prerequisites

- JDK 21 or newer
- Maven (recommended 3.6+)

Clone & Build

```powershell
git clone <repo-url>
cd FinderX
mvn clean package
```

Run the app

```powershell
java -jar target/FinderX-1.0.jar
```

Use the installer

Download the signed installer from the Releases page (FinderX-Setup-<version>.exe) and run the installer.

Try an example plugin

1. Open `examples/recent-files-plugin`
2. Build the example plugin: `mvn clean package`
3. Copy the resulting JAR into the FinderX plugin directory or start FinderX with the plugin on the classpath

Common issues

- "Cannot find or load main class": Check JDK version and that the JAR was built successfully.
- UI or theme assets missing: Verify `src/main/resources/theme` and `docs/themes.md`.

Need more help? See `docs/Development.md` for developer-focused instructions.
