# FinderX Plugin API (Java)

Diese Dokumentation beschreibt die aktuelle FinderX Plugin-API im Detail.

## Ziele der API

- Erweiterbare Suche über externe Provider
- Klare Lifecycle-Hooks für Plugin-Initialisierung und Shutdown
- Sauberer Zugriff auf App-Kontext (Root, Sprache, App-Home)
- Plugin-spezifische Persistenz (`PluginSettings`)
- Einheitliches Logging und Status-Reporting
- Rückwärtskompatible Pfad-Suche (Legacy-API)

## Laufzeit-Architektur

FinderX lädt Plugin-JARs dynamisch aus:

- `%USERPROFILE%\\.finderx\\plugins\\*.jar`

Loader-Mechanismus:

- `java.util.ServiceLoader`
- Service-Typ:
  - `dev.eministar.plugins.api.SearchProvider`

Isolierung:

- Jedes Plugin-JAR wird über einen separaten `URLClassLoader` geladen.
- Fehler in einem Plugin sollen den Rest nicht blockieren.

## API-Pakete

- `dev.eministar.plugins.api.PluginContext`
- `dev.eministar.plugins.api.SearchProvider`
- `dev.eministar.plugins.api.SearchRequest`
- `dev.eministar.plugins.api.SearchResult`
- `dev.eministar.plugins.api.PluginCapability`
- `dev.eministar.plugins.api.PluginDescriptor`
- `dev.eministar.plugins.api.PluginLogger`
- `dev.eministar.plugins.api.PluginSettings`

## SearchProvider

### Pflichtmethoden

```java
String id();
String name();
List<Path> search(String query, int limit, PluginContext context);
```

### Empfohlene Metadaten

```java
default String version() { return "1.0.0"; }
default String description() { return ""; }
default String author() { return ""; }
default String homepage() { return ""; }
default Set<PluginCapability> capabilities() { ... }
```

### Lifecycle-Hooks

```java
default void onLoad(PluginContext context) {}
default void onEnable(PluginContext context) {}
default void onDisable(PluginContext context) {}
default void onSettingsChanged(PluginContext context) {}
default void onUnload() {}
```

Empfehlung:

- Teure Initialisierung in `onLoad()`
- Runtime-Listener in `onEnable()`
- Listener/Threads sauber stoppen in `onDisable()` / `onUnload()`

### Erweiterte Suche (Scoring + Metadaten)

```java
default List<SearchResult> search(SearchRequest request, PluginContext context) { ... }
```

Wenn du nur die Legacy-Methode implementierst, mappt FinderX intern automatisch zu `SearchResult` mit Default-Score.

## SearchRequest

```java
public record SearchRequest(
    String query,
    int limit,
    Path activeRoot,
    String languageCode,
    Map<String, String> filters
) {}
```

Hinweise:

- `limit` ist ein hartes Budget je Anfrage
- `activeRoot` zeigt das aktuell selektierte Drive/Root in der UI
- `filters` ist für zukünftige Query-Felder vorbereitet

## SearchResult

```java
public record SearchResult(
    Path path,
    double score,
    String sourceId,
    String displayName,
    Map<String, String> metadata
) {}
```

Empfehlung für `score`:

- Bereich: `0.0` bis `1.0`
- `1.0` = sehr hohe Relevanz
- Sortierung erfolgt absteigend nach Score

## PluginContext

### Basisdaten

- `Path appHome()`
- `Path activeRoot()`
- `String languageCode()`

### Erweiterte Services

- `Path pluginDataDir(String pluginId)`
  - persistente Plugin-Daten unter `%USERPROFILE%\\.finderx\\plugins-data\\<pluginId>`
- `PluginLogger logger(String pluginId)`
- `PluginSettings settings(String pluginId)`
- `void reportStatus(String pluginId, String message)`

## PluginLogger

```java
void debug(String message);
void info(String message);
void warn(String message);
void error(String message, Throwable throwable);
```

Best Practice:

- keine sensitive Daten loggen
- kurze, strukturierte Meldungen

## PluginSettings

```java
String getString(String key, String fallback);
int getInt(String key, int fallback);
boolean getBoolean(String key, boolean fallback);

void putString(String key, String value);
void putInt(String key, int value);
void putBoolean(String key, boolean value);
void remove(String key);
void save();
```

Hinweis:

- `save()` bewusst explizit aufrufen (batch writes)

## PluginCapability

Derzeit definierte Capabilities:

- `SEARCH_PATHS`
- `SEARCH_SCORING`
- `LIVE_UPDATES`
- `SETTINGS_UI`
- `COMMANDS`

Nutzen:

- Self-Description
- spätere UI-/Policy-Steuerung

## ServiceLoader-Registrierung

Pflichtdatei im Plugin-JAR:

- `META-INF/services/dev.eministar.plugins.api.SearchProvider`

Inhalt:

```text
com.example.finderx.plugin.MySearchProvider
```

## Minimales Plugin-Beispiel

```java
public final class MyProvider implements SearchProvider {
    @Override
    public String id() { return "my-provider"; }

    @Override
    public String name() { return "My Provider"; }

    @Override
    public List<Path> search(String query, int limit, PluginContext context) {
        return List.of();
    }
}
```

## Erweitertes Beispiel (SearchRequest + Score)

```java
@Override
public List<SearchResult> search(SearchRequest request, PluginContext context) {
    if (request.query() == null || request.query().isBlank()) return List.of();
    return List.of(
        new SearchResult(
            Path.of("C:\\demo.txt"),
            0.92,
            id(),
            "Demo Treffer",
            Map.of("type", "demo")
        )
    );
}
```

## API-JAR bauen

```powershell
pwsh -File scripts/build-plugin-api.ps1
```

Output:

- `dist/FinderX-Plugin-API.jar`

Enthält nur die API-Klassen (`dev/eministar/plugins/api/*`), damit Plugins keine FinderX-Interna linken müssen.

## Beispiel-Plugin im Repository

- `examples/recent-files-plugin/`

Enthalten:

- Provider-Implementierung
- `META-INF/services` Registrierung
- eigenes `pom.xml`

## Deployment

1. Plugin-JAR bauen.
2. JAR in `%USERPROFILE%\\.finderx\\plugins` kopieren.
3. FinderX öffnen.
4. Settings -> Plugins -> `Reload plugins`.

## Plugin-Lebenszyklus

Beim Reload:

1. `onUnload()` alter Plugins
2. ClassLoader-Cleanup
3. neue Plugins laden
4. `onLoad()`
5. falls enabled: `onEnable()`

Beim Deaktivieren:

- `onDisable()`

Beim App-Shutdown:

- `onDisable()` (wenn aktiv)
- `onUnload()`

## Fehlerbehandlung

- Jede Plugin-Operation läuft geschützt in `try/catch`.
- Exceptions werden geloggt.
- Defekte Plugins sollen nicht den gesamten Suchlauf abbrechen.

## Performance-Guidelines

- `search()` muss schnell reagieren (ms statt Sekunden)
- keine blockierenden Netzwerkanfragen auf dem UI-Pfad
- bei teuren Quellen: internes Caching
- `limit` strikt respektieren

## Sicherheit

Plugins laufen im selben Prozess wie FinderX:

- voller Zugriff auf User-Kontext möglich
- nur vertrauenswürdige JARs installieren
- keine unbekannten Plugins aus unsicheren Quellen laden

## Versionierung

Empfehlung:

- Plugin `version()` nach SemVer pflegen
- bei Breaking Changes in API:
  - neue API-Version dokumentieren
  - ggf. Adapter/Fallback bereitstellen

## Troubleshooting

Wenn Plugin nicht geladen wird:

1. Prüfen, ob JAR in `%USERPROFILE%\\.finderx\\plugins` liegt.
2. Prüfen, ob `META-INF/services/...SearchProvider` vorhanden ist.
3. Prüfen, ob Klassenname in Service-Datei exakt stimmt.
4. FinderX Settings -> `Reload plugins`.
5. Logs auf `Plugin ... load/search failed` prüfen.
