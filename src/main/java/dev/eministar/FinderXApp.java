package dev.eministar;

import dev.eministar.core.AppStateStore;
import dev.eministar.core.DiscordPresenceService;
import dev.eministar.core.FileRecord;
import dev.eministar.core.IndexProgress;
import dev.eministar.core.IndexService;
import dev.eministar.core.IndexMode;
import dev.eministar.core.UpdateService;
import dev.eministar.i18n.I18n;
import dev.eministar.i18n.I18nKey;
import dev.eministar.i18n.LanguageOption;
import dev.eministar.plugins.PluginManager;
import dev.eministar.ui.SvgIconLoader;
import dev.eministar.ui.SystemIconProvider;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.util.StringConverter;
import javafx.util.Duration;

import java.awt.Desktop;
import java.awt.Taskbar;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class FinderXApp extends Application {
    private static final String APP_VERSION = "v1.0";
    private static final String RELEASE_API = "";
    private static final String GITHUB_URL = "https://github.com/eministar/FinderX";
    private static final String KOFI_URL = "https://ko-fi.com/eministar";
    private static final String DEFAULT_THEME_RESOURCE = "/theme/dark-glass.css";
    private static final String OFFICIAL_THEME_FILE = "dark-glass.css";
    private static final String OFFICIAL_THEME_AUTHOR = "FinderX Team";
    private static final double RESIZE_MARGIN = 7.0;

    private final IndexService indexService = new IndexService();
    private final UpdateService updateService = new UpdateService();
    private final SystemIconProvider iconProvider = new SystemIconProvider();
    private final AppStateStore appStateStore = new AppStateStore();
    private final PluginManager pluginManager;
    private final DiscordPresenceService discordPresenceService;

    private final ObservableList<FileRecord> rows = FXCollections.observableArrayList();
    private final AtomicLong searchGeneration = new AtomicLong();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(60));

    private final LinkedHashSet<Path> pinnedPaths = new LinkedHashSet<>();
    private final List<Path> recentPaths = new ArrayList<>();
    private final Path themesDir = Path.of(System.getProperty("user.home"), ".finderx", "themes");

    private TableView<FileRecord> table;
    private Label statusLabel;
    private Label phaseLabel;
    private Label autoTuneLabel;
    private Label updateLabel;
    private Label appSubLabel;
    private ProgressBar progressBar;
    private TextField searchField;
    private ComboBox<String> driveSelector;
    private TextField extFilterField;
    private ToggleButton allFilterBtn;
    private ToggleButton filesFilterBtn;
    private ToggleButton foldersFilterBtn;
    private CheckBox recentOnlyCheck;
    private CheckBox smartRankingCheckTop;
    private Button settingsBtn;
    private FlowPane quickAccessPane;
    private TableColumn<FileRecord, String> nameCol;
    private TableColumn<FileRecord, String> typeCol;
    private TableColumn<FileRecord, String> pathCol;

    private double dragOffsetX;
    private double dragOffsetY;
    private boolean resizingWindow;
    private ResizeMode resizeMode = ResizeMode.NONE;
    private double resizeStartScreenX;
    private double resizeStartScreenY;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartW;
    private double resizeStartH;
    private Path activeRoot = Path.of("C:\\");
    private int maxResults = 700;
    private boolean discordPresenceEnabled = true;
    private String appLanguageCode = "en";
    private IndexMode indexMode = IndexMode.AUTO;
    private boolean incrementalOnStartup = true;
    private boolean watchModeEnabled = true;
    private String latestUpdateVersion;
    private boolean customThemeEnabled;
    private String selectedThemeFile = "";
    private Scene mainScene;
    private WatchService watchService;
    private Thread watchThread;

    private enum ResizeMode {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

    private record ThemeSeed(String fileName, String resourcePath) {
    }

    private static final List<ThemeSeed> OFFICIAL_THEME_SEEDS = List.of(
            new ThemeSeed("dark-glass.css", "/theme/dark-glass.css"),
            new ThemeSeed("ocean-night.css", "/theme/ocean-night.css"),
            new ThemeSeed("ember-ash.css", "/theme/ember-ash.css"),
            new ThemeSeed("forest-mint.css", "/theme/forest-mint.css")
    );

    private record ThemeOption(
            String fileName,
            String displayName,
            String author,
            String description,
            boolean official
    ) {
    }

    private Path pluginActiveRoot() {
        return activeRoot == null ? Path.of("C:\\") : activeRoot;
    }

    private String pluginLanguageCode() {
        return appLanguageCode == null || appLanguageCode.isBlank() ? "en" : appLanguageCode;
    }

    private String t(I18nKey key, Object... args) {
        return I18n.tr(appLanguageCode, key, args);
    }

    private String localizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return t(I18nKey.UI_PHASE_READY);
        }
        return switch (phase.toLowerCase(Locale.ROOT)) {
            case "scan" -> t(I18nKey.UI_PHASE_SCAN);
            case "cache" -> t(I18nKey.UI_PHASE_CACHE);
            case "build" -> t(I18nKey.UI_PHASE_BUILD);
            case "search" -> t(I18nKey.UI_PHASE_SEARCH);
            case "ready", "idle" -> t(I18nKey.UI_PHASE_READY);
            default -> phase;
        };
    }

    private void applyLanguageToMainUi() {
        if (appSubLabel != null) {
            appSubLabel.setText(t(I18nKey.APP_SUBTITLE));
        }
        if (settingsBtn != null) {
            settingsBtn.setText(t(I18nKey.UI_SETTINGS));
        }
        if (updateLabel != null) {
            if (latestUpdateVersion == null || latestUpdateVersion.isBlank()) {
                updateLabel.setText(t(I18nKey.UI_VERSION, APP_VERSION));
            } else {
                updateLabel.setText(t(I18nKey.UI_UPDATE_AVAILABLE, latestUpdateVersion));
            }
        }
        if (searchField != null) {
            searchField.setPromptText(t(I18nKey.UI_SEARCH_PROMPT));
        }
        if (allFilterBtn != null) {
            allFilterBtn.setText(t(I18nKey.UI_FILTER_ALL));
        }
        if (filesFilterBtn != null) {
            filesFilterBtn.setText(t(I18nKey.UI_FILTER_FILES));
        }
        if (foldersFilterBtn != null) {
            foldersFilterBtn.setText(t(I18nKey.UI_FILTER_FOLDERS));
        }
        if (extFilterField != null) {
            extFilterField.setPromptText(t(I18nKey.UI_EXT_FILTER_PROMPT));
        }
        if (recentOnlyCheck != null) {
            recentOnlyCheck.setText(t(I18nKey.UI_RECENT_30D));
        }
        if (smartRankingCheckTop != null) {
            smartRankingCheckTop.setText(t(I18nKey.UI_SMART_RANK_SHORT));
        }
        if (table != null) {
            table.setPlaceholder(new Label(t(I18nKey.UI_TABLE_NO_RESULTS)));
        }
        if (nameCol != null) {
            nameCol.setText(t(I18nKey.UI_COL_NAME));
        }
        if (typeCol != null) {
            typeCol.setText(t(I18nKey.UI_COL_TYPE));
        }
        if (pathCol != null) {
            pathCol.setText(t(I18nKey.UI_COL_PATH));
        }

        rebuildQuickAccess();
        String q = searchField == null ? "" : searchField.getText();
        if (q == null || q.isBlank()) {
            if (phaseLabel != null) {
                phaseLabel.setText(t(I18nKey.UI_PHASE_READY));
            }
            if (statusLabel != null) {
                statusLabel.setText(t(I18nKey.UI_STATUS_TYPE_TO_SEARCH));
            }
        } else {
            retriggerSearch();
        }
    }

    private void ensureThemesDir() {
        try {
            Files.createDirectories(themesDir);
        } catch (IOException ignored) {
        }
    }

    private void ensureOfficialThemeFiles() {
        ensureThemesDir();
        for (ThemeSeed seed : OFFICIAL_THEME_SEEDS) {
            Path target = themesDir.resolve(seed.fileName());
            try (var in = getClass().getResourceAsStream(seed.resourcePath())) {
                if (in != null) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private List<ThemeOption> scanThemes() {
        ensureOfficialThemeFiles();
        List<ThemeOption> out = new ArrayList<>();
        try (var stream = Files.list(themesDir)) {
            stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName() != null
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".css"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        ThemeOption meta = parseThemeMetadata(path);
                        boolean official = meta.official();
                        String name = meta.displayName();
                        if (name == null || name.isBlank()) {
                            name = fileName;
                        }
                        String label = official ? name + " " + t(I18nKey.SETTINGS_THEME_OFFICIAL_TAG) : name;
                        out.add(new ThemeOption(
                                fileName,
                                label,
                                meta.author(),
                                meta.description(),
                                official
                        ));
                    });
        } catch (IOException ignored) {
        }
        return out;
    }

    private ThemeOption parseThemeMetadata(Path cssFile) {
        String fileName = cssFile.getFileName() == null ? "" : cssFile.getFileName().toString();
        String fallbackName = OFFICIAL_THEME_FILE.equalsIgnoreCase(fileName)
                ? t(I18nKey.SETTINGS_THEME_DEFAULT_DARK_GLASS)
                : fileName;
        String name = fallbackName;
        String author = OFFICIAL_THEME_AUTHOR;
        String description = "";
        boolean official = isOfficialTheme(fileName);

        try {
            List<String> lines = Files.readAllLines(cssFile);
            for (String raw : lines) {
                String line = raw == null ? "" : raw.trim();
                line = line.replace("/*", "").replace("*/", "").trim();
                if (line.isEmpty()) {
                    continue;
                }
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("fx-theme-name:")) {
                    name = line.substring("fx-theme-name:".length()).trim();
                } else if (lower.startsWith("fx-theme-author:")) {
                    author = line.substring("fx-theme-author:".length()).trim();
                } else if (lower.startsWith("fx-theme-description:")) {
                    description = line.substring("fx-theme-description:".length()).trim();
                }
            }
        } catch (IOException ignored) {
        }

        if (author == null || author.isBlank()) {
            author = OFFICIAL_THEME_AUTHOR;
        }
        if (name == null || name.isBlank()) {
            name = fallbackName;
        }
        if (description == null) {
            description = "";
        }
        return new ThemeOption(fileName, name, author, description, official);
    }

    private boolean isOfficialTheme(String fileName) {
        for (ThemeSeed seed : OFFICIAL_THEME_SEEDS) {
            if (seed.fileName().equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return false;
    }

    private Path createNewThemeFile() throws IOException {
        ensureOfficialThemeFiles();
        String base = "custom-theme";
        int idx = 1;
        Path target = themesDir.resolve(base + ".css");
        while (Files.exists(target)) {
            idx++;
            target = themesDir.resolve(base + "-" + idx + ".css");
        }
        String baseCss = Files.readString(themesDir.resolve(OFFICIAL_THEME_FILE));
        baseCss = baseCss.replaceFirst("(?s)^/\\*.*?\\*/\\s*", "");
        String metadata = String.join(System.lineSeparator(),
                "/*",
                "fx-theme-name: My Theme",
                "fx-theme-author: Your Name",
                "fx-theme-description: Describe your theme here.",
                "*/",
                ""
        );
        Files.writeString(target, metadata + System.lineSeparator() + baseCss);
        return target;
    }

    private void openThemeInEditor(Path file) throws IOException {
        if (file == null || !Desktop.isDesktopSupported()) {
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.EDIT)) {
            desktop.edit(file.toFile());
            return;
        }
        desktop.open(file.toFile());
    }

    private String resolveActiveThemeStylesheet() {
        String fallback = getClass().getResource(DEFAULT_THEME_RESOURCE).toExternalForm();
        if (selectedThemeFile == null || selectedThemeFile.isBlank()) {
            return fallback;
        }
        Path css = themesDir.resolve(selectedThemeFile).normalize();
        if (!Files.exists(css) || !Files.isRegularFile(css)) {
            return fallback;
        }
        return css.toUri().toString();
    }

    private void applyThemeToScene(Scene scene) {
        if (scene == null) {
            return;
        }
        scene.getStylesheets().setAll(resolveActiveThemeStylesheet());
    }

    private void applyThemeToMainScene() {
        applyThemeToScene(mainScene);
    }

    public FinderXApp() {
        this.pluginManager = new PluginManager(this::pluginActiveRoot, this::pluginLanguageCode);
        this.discordPresenceService = createDiscordPresenceService();
    }

    public static void launchApp(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        loadState();

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setResizable(true);
        stage.setMinWidth(980);
        stage.setMinHeight(620);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setPadding(new Insets(12));

        VBox topBox = buildTopBar();
        StackPane center = new StackPane(buildResultsPane());
        center.getStyleClass().add("center-card");
        center.setPadding(new Insets(8));
        HBox bottom = buildBottomBar();

        VBox content = new VBox(8, buildWindowBar(stage), topBox, center, bottom);
        VBox.setVgrow(center, Priority.ALWAYS);
        root.setCenter(content);

        StackPane shell = new StackPane(root);
        shell.getStyleClass().add("window-shell");

        Rectangle clip = new Rectangle();
        clip.setArcWidth(26);
        clip.setArcHeight(26);
        clip.widthProperty().bind(shell.widthProperty());
        clip.heightProperty().bind(shell.heightProperty());
        shell.setClip(clip);

        Scene scene = new Scene(shell, 1450, 920);
        scene.setFill(Color.TRANSPARENT);
        mainScene = scene;
        applyThemeToScene(scene);
        stage.setScene(scene);
        stage.setTitle("FinderX");
        installResizeSupport(stage, scene);

        installShortcuts(scene);

        Image appIcon = loadAppIcon();
        if (appIcon != null) {
            stage.getIcons().add(appIcon);
            setWindowsTaskbarIcon(appIcon);
        }

        stage.show();
        startIndexing();
        checkForUpdates();
        if (discordPresenceService != null) {
            discordPresenceService.setEnabled(discordPresenceEnabled);
            discordPresenceService.start();
            discordPresenceService.updateIdle(t(I18nKey.DISCORD_READY));
        }
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private void installResizeSupport(Stage stage, Scene scene) {
        scene.setOnMouseMoved(e -> {
            if (stage.isMaximized()) {
                scene.setCursor(Cursor.DEFAULT);
                return;
            }
            ResizeMode mode = detectResizeMode(e.getSceneX(), e.getSceneY(), stage.getWidth(), stage.getHeight());
            scene.setCursor(cursorFor(mode));
        });

        scene.setOnMousePressed(e -> {
            if (stage.isMaximized()) {
                resizingWindow = false;
                return;
            }
            resizeMode = detectResizeMode(e.getSceneX(), e.getSceneY(), stage.getWidth(), stage.getHeight());
            resizingWindow = resizeMode != ResizeMode.NONE;
            if (!resizingWindow) {
                return;
            }
            resizeStartScreenX = e.getScreenX();
            resizeStartScreenY = e.getScreenY();
            resizeStartX = stage.getX();
            resizeStartY = stage.getY();
            resizeStartW = stage.getWidth();
            resizeStartH = stage.getHeight();
        });

        scene.setOnMouseDragged(e -> {
            if (!resizingWindow || stage.isMaximized()) {
                return;
            }
            double dx = e.getScreenX() - resizeStartScreenX;
            double dy = e.getScreenY() - resizeStartScreenY;

            double minW = stage.getMinWidth() > 0 ? stage.getMinWidth() : 980;
            double minH = stage.getMinHeight() > 0 ? stage.getMinHeight() : 620;

            double x = resizeStartX;
            double y = resizeStartY;
            double w = resizeStartW;
            double h = resizeStartH;

            if (resizeMode == ResizeMode.E || resizeMode == ResizeMode.NE || resizeMode == ResizeMode.SE) {
                w = Math.max(minW, resizeStartW + dx);
            }
            if (resizeMode == ResizeMode.S || resizeMode == ResizeMode.SE || resizeMode == ResizeMode.SW) {
                h = Math.max(minH, resizeStartH + dy);
            }
            if (resizeMode == ResizeMode.W || resizeMode == ResizeMode.NW || resizeMode == ResizeMode.SW) {
                double target = resizeStartW - dx;
                if (target < minW) {
                    x = resizeStartX + (resizeStartW - minW);
                    w = minW;
                } else {
                    x = resizeStartX + dx;
                    w = target;
                }
            }
            if (resizeMode == ResizeMode.N || resizeMode == ResizeMode.NE || resizeMode == ResizeMode.NW) {
                double target = resizeStartH - dy;
                if (target < minH) {
                    y = resizeStartY + (resizeStartH - minH);
                    h = minH;
                } else {
                    y = resizeStartY + dy;
                    h = target;
                }
            }

            stage.setX(x);
            stage.setY(y);
            stage.setWidth(w);
            stage.setHeight(h);
        });

        scene.setOnMouseReleased(e -> {
            resizingWindow = false;
            resizeMode = ResizeMode.NONE;
        });
    }

    private ResizeMode detectResizeMode(double sceneX, double sceneY, double width, double height) {
        boolean left = sceneX <= RESIZE_MARGIN;
        boolean right = sceneX >= width - RESIZE_MARGIN;
        boolean top = sceneY <= RESIZE_MARGIN;
        boolean bottom = sceneY >= height - RESIZE_MARGIN;

        if (top && left) return ResizeMode.NW;
        if (top && right) return ResizeMode.NE;
        if (bottom && left) return ResizeMode.SW;
        if (bottom && right) return ResizeMode.SE;
        if (top) return ResizeMode.N;
        if (bottom) return ResizeMode.S;
        if (left) return ResizeMode.W;
        if (right) return ResizeMode.E;
        return ResizeMode.NONE;
    }

    private Cursor cursorFor(ResizeMode mode) {
        return switch (mode) {
            case N -> Cursor.N_RESIZE;
            case S -> Cursor.S_RESIZE;
            case E -> Cursor.E_RESIZE;
            case W -> Cursor.W_RESIZE;
            case NE -> Cursor.NE_RESIZE;
            case NW -> Cursor.NW_RESIZE;
            case SE -> Cursor.SE_RESIZE;
            case SW -> Cursor.SW_RESIZE;
            default -> Cursor.DEFAULT;
        };
    }

    @Override
    public void stop() {
        stopWatchMode();
        appStateStore.savePinned(pinnedPaths);
        appStateStore.saveRecent(recentPaths);
        appStateStore.saveSmartRankingEnabled(indexService.isSmartRankingEnabled());
        appStateStore.saveUsageScores(indexService.usageScoresSnapshot());
        appStateStore.saveSelectedRoot(activeRoot.toString());
        appStateStore.saveMaxResults(maxResults);
        appStateStore.saveDiscordPresenceEnabled(discordPresenceEnabled);
        appStateStore.saveLanguage(appLanguageCode);
        appStateStore.saveIndexMode(indexMode.name().toLowerCase(Locale.ROOT));
        appStateStore.saveIncrementalOnStartup(incrementalOnStartup);
        appStateStore.saveWatchModeEnabled(watchModeEnabled);
        appStateStore.saveCustomThemeEnabled(customThemeEnabled);
        appStateStore.saveSelectedThemeFile(selectedThemeFile);
        if (discordPresenceService != null) {
            discordPresenceService.stop();
        }
        indexService.shutdown();
        pluginManager.shutdown();
        iconProvider.shutdown();
    }

    private void loadState() {
        I18n.initialize();
        pinnedPaths.addAll(appStateStore.loadPinned());
        recentPaths.addAll(appStateStore.loadRecent());
        indexService.setUsageScores(appStateStore.loadUsageScores());
        indexService.setSmartRankingEnabled(appStateStore.loadSmartRankingEnabled());
        maxResults = Math.max(100, Math.min(5000, appStateStore.loadMaxResults()));
        discordPresenceEnabled = appStateStore.loadDiscordPresenceEnabled();
        appLanguageCode = I18n.resolveLanguageCode(appStateStore.loadLanguage());
        indexMode = IndexMode.fromString(appStateStore.loadIndexMode());
        incrementalOnStartup = appStateStore.loadIncrementalOnStartup();
        watchModeEnabled = appStateStore.loadWatchModeEnabled();
        indexService.setIndexMode(indexMode);
        indexService.setIncrementalOnStartup(incrementalOnStartup);
        pluginManager.reload();
        customThemeEnabled = appStateStore.loadCustomThemeEnabled();
        selectedThemeFile = appStateStore.loadSelectedThemeFile();
        ensureOfficialThemeFiles();
        try {
            activeRoot = Path.of(appStateStore.loadSelectedRoot());
        } catch (Exception ignored) {
            activeRoot = Path.of("C:\\");
        }
    }

    private HBox buildWindowBar(Stage stage) {
        Label title = new Label("FinderX");
        title.getStyleClass().add("window-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minBtn = new Button("—");
        minBtn.getStyleClass().addAll("window-btn", "window-btn-min");
        minBtn.setOnAction(e -> stage.setIconified(true));

        Button maxBtn = new Button("□");
        maxBtn.getStyleClass().addAll("window-btn", "window-btn-max");
        maxBtn.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().addAll("window-btn", "window-btn-close");
        closeBtn.setOnAction(e -> stage.close());

        HBox bar = new HBox(8, title, spacer, minBtn, maxBtn, closeBtn);
        bar.getStyleClass().add("window-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 6, 12));
        stage.maximizedProperty().addListener((obs, old, isMax) -> maxBtn.setText(isMax ? "❐" : "□"));

        bar.setOnMousePressed(e -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        bar.setOnMouseDragged(e -> {
            if (stage.isMaximized()) {
                double ratio = bar.getWidth() <= 0 ? 0.5 : Math.max(0.0, Math.min(1.0, e.getX() / bar.getWidth()));
                stage.setMaximized(false);
                dragOffsetX = stage.getWidth() * ratio;
                stage.setX(e.getScreenX() - dragOffsetX);
                stage.setY(e.getScreenY() - dragOffsetY);
                return;
            }
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
        bar.setOnMouseReleased(e -> applyEdgeSnap(stage, e.getScreenX(), e.getScreenY()));
        bar.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                stage.setMaximized(!stage.isMaximized());
            }
        });
        return bar;
    }

    private VBox buildTopBar() {
        ImageView logoView = new ImageView();
        var logoStream = getClass().getResourceAsStream("/icons/app-logo.png");
        if (logoStream != null) {
            logoView.setImage(new Image(logoStream));
        }
        logoView.setFitWidth(42);
        logoView.setFitHeight(42);
        logoView.getStyleClass().add("app-logo");

        Label appTitle = new Label("FinderX");
        appTitle.getStyleClass().add("app-title");
        appSubLabel = new Label(t(I18nKey.APP_SUBTITLE));
        appSubLabel.getStyleClass().add("app-subtitle");

        VBox appInfo = new VBox(0, appTitle, appSubLabel);
        HBox brand = new HBox(10, logoView, appInfo);
        brand.setAlignment(Pos.CENTER_LEFT);

        updateLabel = new Label(t(I18nKey.UI_VERSION, APP_VERSION));
        updateLabel.getStyleClass().add("update-label");

        driveSelector = new ComboBox<>();
        driveSelector.getStyleClass().add("drive-select");
        for (java.io.File root : java.io.File.listRoots()) {
            driveSelector.getItems().add(root.getAbsolutePath());
        }
        if (!driveSelector.getItems().contains(activeRoot.toString())) {
            driveSelector.getItems().add(activeRoot.toString());
        }
        driveSelector.setValue(activeRoot.toString());
        driveSelector.setPrefHeight(34);
        driveSelector.setMinHeight(34);
        driveSelector.setMaxHeight(34);
        driveSelector.setOnAction(e -> {
            String selected = driveSelector.getValue();
            if (selected == null || selected.isBlank()) {
                return;
            }
            activeRoot = Path.of(selected);
            appStateStore.saveSelectedRoot(selected);
            startIndexing();
            if (discordPresenceService != null) {
                discordPresenceService.updateIdle(t(I18nKey.DISCORD_BROWSING, selected));
            }
        });

        settingsBtn = new Button(t(I18nKey.UI_SETTINGS));
        settingsBtn.getStyleClass().add("settings-btn");
        settingsBtn.setPrefHeight(34);
        settingsBtn.setMinHeight(34);
        settingsBtn.setMaxHeight(34);
        settingsBtn.setOnAction(e -> showSettingsDialog());

        updateLabel.setPrefHeight(34);
        updateLabel.setMinHeight(34);
        updateLabel.setMaxHeight(34);

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        HBox headline = new HBox(12, brand, topSpacer, driveSelector, settingsBtn, updateLabel);
        headline.setAlignment(Pos.CENTER_LEFT);

        searchField = new TextField();
        searchField.setPromptText(t(I18nKey.UI_SEARCH_PROMPT));
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        allFilterBtn = new ToggleButton(t(I18nKey.UI_FILTER_ALL));
        filesFilterBtn = new ToggleButton(t(I18nKey.UI_FILTER_FILES));
        foldersFilterBtn = new ToggleButton(t(I18nKey.UI_FILTER_FOLDERS));
        ToggleGroup group = new ToggleGroup();
        allFilterBtn.setToggleGroup(group);
        filesFilterBtn.setToggleGroup(group);
        foldersFilterBtn.setToggleGroup(group);
        allFilterBtn.setSelected(true);

        allFilterBtn.getStyleClass().add("filter-chip");
        filesFilterBtn.getStyleClass().add("filter-chip");
        foldersFilterBtn.getStyleClass().add("filter-chip");

        extFilterField = new TextField();
        extFilterField.setPromptText(t(I18nKey.UI_EXT_FILTER_PROMPT));
        extFilterField.getStyleClass().add("chip-input");
        extFilterField.setPrefWidth(120);

        recentOnlyCheck = new CheckBox(t(I18nKey.UI_RECENT_30D));
        recentOnlyCheck.getStyleClass().add("chip-check");

        smartRankingCheckTop = new CheckBox(t(I18nKey.UI_SMART_RANK_SHORT));
        smartRankingCheckTop.setSelected(indexService.isSmartRankingEnabled());
        smartRankingCheckTop.getStyleClass().add("chip-check");
        smartRankingCheckTop.selectedProperty().addListener((obs, old, val) -> {
            indexService.setSmartRankingEnabled(val);
            appStateStore.saveSmartRankingEnabled(val);
            retriggerSearch();
        });

        HBox filterRow = new HBox(8, allFilterBtn, filesFilterBtn, foldersFilterBtn, extFilterField, recentOnlyCheck, smartRankingCheckTop);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        HBox searchRow = new HBox(10, searchField);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        quickAccessPane = new FlowPane();
        quickAccessPane.setHgap(8);
        quickAccessPane.setVgap(8);
        quickAccessPane.getStyleClass().add("quick-access");
        rebuildQuickAccess();

        VBox topContainer = new VBox(10, headline, searchRow, filterRow, quickAccessPane);
        topContainer.setPadding(new Insets(12));
        topContainer.getStyleClass().add("top-container");

        searchField.textProperty().addListener((obs, old, value) -> debounce.playFromStart());
        extFilterField.textProperty().addListener((obs, old, value) -> retriggerSearch());
        recentOnlyCheck.selectedProperty().addListener((obs, old, value) -> retriggerSearch());
        group.selectedToggleProperty().addListener((obs, old, value) -> retriggerSearch());

        debounce.setOnFinished(evt -> {
            String q = searchField.getText();
            if (q == null || q.isBlank()) {
                rows.clear();
                phaseLabel.setText(t(I18nKey.UI_PHASE_READY));
                statusLabel.setText(t(I18nKey.UI_STATUS_TYPE_TO_SEARCH));
                if (discordPresenceService != null) {
                    discordPresenceService.updateIdle(t(I18nKey.DISCORD_READY));
                }
                return;
            }
            runSearch(q);
        });

        return new VBox(topContainer);
    }

    private TableView<FileRecord> buildResultsPane() {
        table = new TableView<>(rows);
        table.getStyleClass().add("file-table");
        table.setPlaceholder(new Label(t(I18nKey.UI_TABLE_NO_RESULTS)));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);

        nameCol = new TableColumn<>(t(I18nKey.UI_COL_NAME));
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
        nameCol.setPrefWidth(420);
        nameCol.setCellFactory(col -> new TableCell<>() {
            private final ImageView iconView = new ImageView();
            private final Label text = new Label();
            private final HBox box = new HBox(8, iconView, text);

            {
                iconView.setFitWidth(16);
                iconView.setFitHeight(16);
                box.setAlignment(Pos.CENTER_LEFT);
                text.getStyleClass().add("name-cell-label");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                FileRecord record = (FileRecord) getTableRow().getItem();
                text.setText(item);
                iconProvider.loadIconAsync(record.path(), record.directory(), iconView::setImage);
                setGraphic(box);
            }
        });

        typeCol = new TableColumn<>(t(I18nKey.UI_COL_TYPE));
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().extension()));
        typeCol.setPrefWidth(120);

        pathCol = new TableColumn<>(t(I18nKey.UI_COL_PATH));
        pathCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().path().toString()));
        pathCol.setPrefWidth(860);

        table.getColumns().addAll(nameCol, typeCol, pathCol);
        table.setRowFactory(tv -> {
            TableRow<FileRecord> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    openRecord(row.getItem());
                }
            });
            row.setContextMenu(buildContextMenu(row));
            return row;
        });

        return table;
    }

    private HBox buildBottomBar() {
        progressBar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.getStyleClass().add("index-progress");
        progressBar.setPrefWidth(220);

        phaseLabel = new Label(t(I18nKey.UI_PHASE_INITIALIZING));
        phaseLabel.getStyleClass().add("phase-label");

        autoTuneLabel = new Label("");
        autoTuneLabel.getStyleClass().add("phase-label");
        autoTuneLabel.setVisible(false);
        autoTuneLabel.setManaged(false);

        statusLabel = new Label(t(I18nKey.UI_STATUS_PREPARING_INDEX));
        statusLabel.getStyleClass().add("status-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(12, phaseLabel, progressBar, autoTuneLabel, spacer, statusLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 12, 10, 12));
        bar.getStyleClass().add("bottom-bar");
        return bar;
    }

    private ContextMenu buildContextMenu(TableRow<FileRecord> row) {
        MenuItem open = new MenuItem(t(I18nKey.UI_MENU_OPEN));
        open.setOnAction(e -> {
            if (!row.isEmpty()) {
                openRecord(row.getItem());
            }
        });

        MenuItem openParent = new MenuItem(t(I18nKey.UI_MENU_OPEN_PARENT));
        openParent.setOnAction(e -> {
            if (!row.isEmpty() && row.getItem().parent() != null) {
                openPath(row.getItem().parent());
            }
        });

        MenuItem pinToggle = new MenuItem(t(I18nKey.UI_MENU_PIN_TOGGLE));
        pinToggle.setOnAction(e -> {
            if (row.isEmpty()) {
                return;
            }
            Path p = row.getItem().path();
            if (!pinnedPaths.add(p)) {
                pinnedPaths.remove(p);
            }
            appStateStore.savePinned(pinnedPaths);
            rebuildQuickAccess();
        });

        MenuItem copyPath = new MenuItem(t(I18nKey.UI_MENU_COPY_PATH));
        copyPath.setOnAction(e -> {
            if (!row.isEmpty()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(row.getItem().path().toString());
                Clipboard.getSystemClipboard().setContent(content);
            }
        });

        MenuItem delete = new MenuItem(t(I18nKey.UI_MENU_DELETE));
        delete.setOnAction(e -> {
            if (row.isEmpty()) {
                return;
            }
            try {
                Files.deleteIfExists(row.getItem().path());
                rows.remove(row.getItem());
            } catch (IOException ex) {
                statusLabel.setText(t(I18nKey.UI_STATUS_DELETE_FAILED, ex.getMessage()));
            }
        });
        return new ContextMenu(open, openParent, pinToggle, copyPath, delete);
    }

    private void installShortcuts(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN), () -> searchField.requestFocus());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), this::copySelectedPath);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ENTER), () -> {
            FileRecord selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                openRecord(selected);
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.UP, KeyCombination.ALT_DOWN), this::openParentFromSelection);
    }

    private void copySelectedPath() {
        FileRecord selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(selected.path().toString());
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText(t(I18nKey.UI_STATUS_PATH_COPIED));
    }

    private void openParentFromSelection() {
        FileRecord selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.parent() == null) {
            return;
        }
        openPath(selected.parent());
    }

    private void startIndexing() {
        stopWatchMode();
        indexService.startIndex(activeRoot, this::onIndexProgress);
        rows.clear();
        statusLabel.setText(t(I18nKey.UI_STATUS_TYPE_TO_SEARCH));
        if (watchModeEnabled) {
            startWatchMode();
        }
        if (discordPresenceService != null) {
            discordPresenceService.updateIndexing(activeRoot.toString(), 0);
        }
    }

    private void startWatchMode() {
        if (!watchModeEnabled) {
            return;
        }
        stopWatchMode();
        try {
            watchService = activeRoot.getFileSystem().newWatchService();
            registerAllDirectories(activeRoot, watchService);
            watchThread = new Thread(() -> runWatchLoop(activeRoot, watchService), "finderx-watch");
            watchThread.setDaemon(true);
            watchThread.start();
        } catch (Exception ignored) {
            stopWatchMode();
        }
    }

    private void stopWatchMode() {
        Thread localThread = watchThread;
        watchThread = null;
        if (localThread != null) {
            localThread.interrupt();
        }

        WatchService localService = watchService;
        watchService = null;
        if (localService != null) {
            try {
                localService.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void runWatchLoop(Path root, WatchService service) {
        long lastTriggerNanos = 0L;
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = service.take();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                return;
            }

            boolean changed = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                changed = true;
                Object context = event.context();
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && context instanceof Path created) {
                    Path watchedDir = (Path) key.watchable();
                    Path full = watchedDir.resolve(created);
                    if (Files.isDirectory(full)) {
                        registerDirectorySafe(full, service);
                    }
                }
            }
            key.reset();

            if (changed) {
                long now = System.nanoTime();
                if (now - lastTriggerNanos > 800_000_000L) {
                    lastTriggerNanos = now;
                    Platform.runLater(() -> {
                        if (watchModeEnabled && activeRoot.equals(root) && !indexService.isIndexing()) {
                            startIndexing();
                        }
                    });
                }
            }
        }
    }

    private void registerAllDirectories(Path root, WatchService service) {
        registerDirectorySafe(root, service);
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isDirectory).forEach(path -> registerDirectorySafe(path, service));
        } catch (IOException ignored) {
        }
    }

    private void registerDirectorySafe(Path dir, WatchService service) {
        try {
            dir.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
            );
        } catch (Exception ignored) {
        }
    }

    private void onIndexProgress(IndexProgress progress) {
        Platform.runLater(() -> {
            if (progress.running()) {
                statusLabel.setText(t(I18nKey.UI_STATUS_INDEXING, progress.filesIndexed(), progress.directoriesIndexed()));
                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                phaseLabel.setText(localizePhase(progress.phase()));
                if (autoTuneLabel != null) {
                    boolean show = "scan".equalsIgnoreCase(progress.phase()) && progress.autoTuneActive();
                    autoTuneLabel.setVisible(show);
                    autoTuneLabel.setManaged(show);
                    if (show) {
                        autoTuneLabel.setText(t(
                                I18nKey.UI_AUTOTUNE_STATUS,
                                progress.activeWorkers(),
                                progress.maxWorkers(),
                                formatRate(progress.itemsPerSecond()),
                                progress.queueDepth()
                        ));
                    } else {
                        autoTuneLabel.setText("");
                    }
                }
                if (discordPresenceService != null) {
                    discordPresenceService.updateIndexing(activeRoot.toString(), progress.filesIndexed());
                }
            } else {
                statusLabel.setText(t(I18nKey.UI_STATUS_INDEX_READY, progress.filesIndexed(), progress.directoriesIndexed()));
                progressBar.setProgress(1.0);
                phaseLabel.setText(t(I18nKey.UI_PHASE_READY));
                if (autoTuneLabel != null) {
                    autoTuneLabel.setText("");
                    autoTuneLabel.setVisible(false);
                    autoTuneLabel.setManaged(false);
                }
                retriggerSearch();
                if (discordPresenceService != null) {
                    discordPresenceService.updateIdle(t(I18nKey.DISCORD_READY));
                }
            }
        });
    }

    private String formatRate(long itemsPerSecond) {
        if (itemsPerSecond < 0) {
            return "-";
        }
        if (itemsPerSecond >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", itemsPerSecond / 1_000_000.0);
        }
        if (itemsPerSecond >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fk", itemsPerSecond / 1_000.0);
        }
        return Long.toString(itemsPerSecond);
    }

    private void checkForUpdates() {
        updateService.checkLatestVersionAsync(RELEASE_API, APP_VERSION)
                .thenAccept(update -> Platform.runLater(() -> update.ifPresent(v -> {
                    latestUpdateVersion = v;
                    updateLabel.setText(t(I18nKey.UI_UPDATE_AVAILABLE, v));
                })));
    }

    private void retriggerSearch() {
        String q = searchField == null ? "" : searchField.getText();
        if (q == null || q.isBlank()) {
            return;
        }
        runSearch(q);
    }

    private void runSearch(String query) {
        long gen = searchGeneration.incrementAndGet();
        phaseLabel.setText(t(I18nKey.UI_PHASE_SEARCH));
        int limit = maxResults;
        indexService.searchAsync(query, limit).thenAccept(found -> Platform.runLater(() -> {
            if (gen != searchGeneration.get()) {
                return;
            }
            List<FileRecord> merged = mergePluginResults(found, query, limit);
            List<FileRecord> filtered = applyFilters(merged);
            rows.setAll(filtered);
            statusLabel.setText(t(I18nKey.UI_RESULTS_COUNT, filtered.size()));
            if (discordPresenceService != null) {
                discordPresenceService.updateSearch(query, filtered.size(), activeRoot.toString());
            }
        }));
    }

    private List<FileRecord> mergePluginResults(List<FileRecord> base, String query, int limit) {
        if (query == null || query.isBlank()) {
            return base;
        }
        List<Path> pluginHits = pluginManager.search(query, Math.max(0, limit - base.size()));
        if (pluginHits.isEmpty()) {
            return base;
        }

        LinkedHashSet<Path> seen = new LinkedHashSet<>();
        List<FileRecord> out = new ArrayList<>(Math.min(limit, base.size() + pluginHits.size()));

        for (FileRecord record : base) {
            if (out.size() >= limit) {
                return out;
            }
            seen.add(record.path());
            out.add(record);
        }

        int id = out.size();
        for (Path hit : pluginHits) {
            if (out.size() >= limit) {
                break;
            }
            if (hit == null || seen.contains(hit)) {
                continue;
            }
            toFileRecord(hit, id++).ifPresent(record -> {
                seen.add(record.path());
                out.add(record);
            });
        }
        return out;
    }

    private java.util.Optional<FileRecord> toFileRecord(Path path, int id) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            boolean directory = Files.isDirectory(normalized);
            long size = directory ? 0L : Files.size(normalized);
            long modified = Files.getLastModifiedTime(normalized).toMillis();
            String name = normalized.getFileName() == null ? normalized.toString() : normalized.getFileName().toString();
            return java.util.Optional.of(new FileRecord(
                    id,
                    normalized,
                    normalized.getParent(),
                    name,
                    name.toLowerCase(Locale.ROOT),
                    directory,
                    size,
                    modified
            ));
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private List<FileRecord> applyFilters(List<FileRecord> found) {
        List<FileRecord> out = new ArrayList<>(found.size());
        String extFilter = extFilterField == null ? "" : extFilterField.getText();
        String ext = extFilter == null ? "" : extFilter.trim().toLowerCase(Locale.ROOT);
        boolean recentOnly = recentOnlyCheck != null && recentOnlyCheck.isSelected();
        Instant threshold = Instant.now().minus(30, ChronoUnit.DAYS);

        for (FileRecord record : found) {
            if (filesFilterBtn != null && filesFilterBtn.isSelected() && record.directory()) {
                continue;
            }
            if (foldersFilterBtn != null && foldersFilterBtn.isSelected() && !record.directory()) {
                continue;
            }
            if (!ext.isEmpty() && !record.extension().toLowerCase(Locale.ROOT).contains(ext)) {
                continue;
            }
            if (recentOnly && record.modifiedEpochMillis() > 0 && record.modifiedInstant().isBefore(threshold)) {
                continue;
            }
            out.add(record);
        }
        return out;
    }

    private void openRecord(FileRecord record) {
        openPath(record.path());
        indexService.recordOpen(record.path());
        appStateStore.saveUsageScores(indexService.usageScoresSnapshot());
        markRecent(record.path());
    }

    private void openPath(Path path) {
        if (!Desktop.isDesktopSupported() || path == null) {
            return;
        }
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException ex) {
            statusLabel.setText(t(I18nKey.UI_STATUS_OPEN_FAILED, ex.getMessage()));
        }
    }

    private void markRecent(Path path) {
        recentPaths.remove(path);
        recentPaths.add(0, path);
        while (recentPaths.size() > 12) {
            recentPaths.removeLast();
        }
        appStateStore.saveRecent(recentPaths);
        rebuildQuickAccess();
    }

    private void rebuildQuickAccess() {
        if (quickAccessPane == null) {
            return;
        }
        quickAccessPane.getChildren().clear();

        for (Path path : pinnedPaths) {
            Button btn = quickAccessButton(t(I18nKey.UI_QUICK_PINNED), path);
            quickAccessPane.getChildren().add(btn);
        }

        int shownRecent = 0;
        for (Path path : recentPaths) {
            if (shownRecent >= 8) {
                break;
            }
            Button btn = quickAccessButton(t(I18nKey.UI_QUICK_RECENT), path);
            quickAccessPane.getChildren().add(btn);
            shownRecent++;
        }
    }

    private Button quickAccessButton(String prefix, Path path) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        if (name.length() > 28) {
            name = name.substring(0, 27) + "...";
        }
        Button btn = new Button(t(I18nKey.UI_QUICK_BUTTON_FORMAT, prefix, name));
        btn.getStyleClass().add("quick-chip");
        btn.setOnAction(e -> openPath(path));
        return btn;
    }

    private HBox buildLanguageCellGraphic(LanguageOption language) {
        ImageView flagIcon = new ImageView(SvgIconLoader.load(language.flagSvgPath(), 22, 14));
        flagIcon.setFitWidth(22);
        flagIcon.setFitHeight(14);
        Label text = new Label(language.displayName());
        text.getStyleClass().add("settings-label");
        HBox row = new HBox(8, flagIcon, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void showSettingsDialog() {
        CheckBox smartRank = new CheckBox(t(I18nKey.SETTINGS_SMART_RANKING));
        smartRank.setSelected(indexService.isSmartRankingEnabled());
        smartRank.getStyleClass().add("chip-check");
        CheckBox discordPresence = new CheckBox(t(I18nKey.SETTINGS_DISCORD_PRESENCE));
        discordPresence.setSelected(discordPresenceEnabled);
        discordPresence.getStyleClass().add("chip-check");
        CheckBox incrementalStartupCheck = new CheckBox(t(I18nKey.SETTINGS_INCREMENTAL_ON_STARTUP));
        incrementalStartupCheck.setSelected(incrementalOnStartup);
        incrementalStartupCheck.getStyleClass().add("chip-check");
        CheckBox watchModeCheck = new CheckBox(t(I18nKey.SETTINGS_WATCH_MODE));
        watchModeCheck.setSelected(watchModeEnabled);
        watchModeCheck.getStyleClass().add("chip-check");

        ComboBox<IndexMode> indexModeSelector = new ComboBox<>();
        indexModeSelector.getItems().addAll(IndexMode.values());
        indexModeSelector.setValue(indexMode);
        indexModeSelector.getStyleClass().add("settings-combo");
        indexModeSelector.setConverter(new StringConverter<>() {
            @Override
            public String toString(IndexMode mode) {
                if (mode == null) {
                    return "";
                }
                return switch (mode) {
                    case AUTO -> t(I18nKey.SETTINGS_INDEX_MODE_AUTO);
                    case INCREMENTAL -> t(I18nKey.SETTINGS_INDEX_MODE_INCREMENTAL);
                    case FULL -> t(I18nKey.SETTINGS_INDEX_MODE_FULL);
                };
            }

            @Override
            public IndexMode fromString(String string) {
                return indexModeSelector.getValue();
            }
        });

        I18n.reload();
        ComboBox<LanguageOption> languageSelector = new ComboBox<>();
        languageSelector.getItems().addAll(I18n.availableLanguages());
        languageSelector.setValue(I18n.findLanguageByCode(appLanguageCode)
                .orElseGet(() -> languageSelector.getItems().isEmpty() ? null : languageSelector.getItems().getFirst()));
        languageSelector.getStyleClass().add("settings-combo");
        languageSelector.setConverter(new StringConverter<>() {
            @Override
            public String toString(LanguageOption language) {
                return language == null ? "" : language.displayName();
            }

            @Override
            public LanguageOption fromString(String string) {
                return languageSelector.getValue();
            }
        });
        languageSelector.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(LanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                    setGraphic(null);
                    return;
                }
                setText(null);
                setGraphic(buildLanguageCellGraphic(item));
            }
        });
        languageSelector.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(LanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                    setGraphic(null);
                    return;
                }
                setText(null);
                setGraphic(buildLanguageCellGraphic(item));
            }
        });

        Spinner<Integer> maxResultsSpinner = new Spinner<>(100, 5000, maxResults, 100);
        maxResultsSpinner.setEditable(true);
        maxResultsSpinner.getStyleClass().add("settings-spinner");

        Button clearCacheBtn = new Button(t(I18nKey.SETTINGS_CLEAR_INDEX_CACHE));
        clearCacheBtn.getStyleClass().add("settings-btn");
        clearCacheBtn.setOnAction(e -> {
            indexService.clearIndexCache();
            statusLabel.setText(t(I18nKey.SETTINGS_STATUS_CACHE_CLEARED));
        });

        Button reloadPluginsBtn = new Button(t(I18nKey.SETTINGS_RELOAD_PLUGINS));
        reloadPluginsBtn.getStyleClass().add("settings-btn");
        reloadPluginsBtn.setOnAction(e -> {
            pluginManager.reload();
            statusLabel.setText(t(I18nKey.SETTINGS_STATUS_PLUGINS_RELOADED));
        });

        Button themeSupportBtn = new Button(t(I18nKey.SETTINGS_THEME_SUPPORT));
        themeSupportBtn.getStyleClass().add("settings-btn");

        Button clearStateBtn = new Button(t(I18nKey.SETTINGS_CLEAR_APP_STATE));
        clearStateBtn.getStyleClass().add("settings-btn");
        clearStateBtn.setOnAction(e -> {
            appStateStore.clearAllState();
            pinnedPaths.clear();
            recentPaths.clear();
            rebuildQuickAccess();
            statusLabel.setText(t(I18nKey.SETTINGS_STATUS_APP_STATE_CLEARED));
        });

        Label perfLabel = new Label(t(I18nKey.SETTINGS_PERFORMANCE));
        perfLabel.getStyleClass().add("settings-section");
        Label pluginLabel = new Label(t(I18nKey.SETTINGS_PLUGINS));
        pluginLabel.getStyleClass().add("settings-section");
        Label maxLabel = new Label(t(I18nKey.SETTINGS_MAX_RESULTS));
        maxLabel.getStyleClass().add("settings-label");
        Label languageLabel = new Label(t(I18nKey.SETTINGS_LANGUAGE));
        languageLabel.getStyleClass().add("settings-label");
        Label indexModeLabel = new Label(t(I18nKey.SETTINGS_INDEX_MODE));
        indexModeLabel.getStyleClass().add("settings-label");

        HBox maxRow = new HBox(10, maxLabel, maxResultsSpinner);
        maxRow.setAlignment(Pos.CENTER_LEFT);
        HBox indexModeRow = new HBox(10, indexModeLabel, indexModeSelector);
        indexModeRow.setAlignment(Pos.CENTER_LEFT);
        HBox languageRow = new HBox(10, languageLabel, languageSelector);
        languageRow.setAlignment(Pos.CENTER_LEFT);

        Label maintenanceLabel = new Label(t(I18nKey.SETTINGS_MAINTENANCE));
        maintenanceLabel.getStyleClass().add("settings-section");

        ImageView githubIcon = new ImageView();
        var ghStream = getClass().getResourceAsStream("/icons/github.png");
        if (ghStream != null) {
            githubIcon.setImage(new Image(ghStream));
        }
        githubIcon.setFitWidth(18);
        githubIcon.setFitHeight(18);
        githubIcon.setPreserveRatio(true);
        githubIcon.setSmooth(true);
        Label ossText = new Label(t(I18nKey.SETTINGS_OSS_TEXT));
        ossText.getStyleClass().add("settings-label");
        Label githubTitle = new Label(t(I18nKey.SETTINGS_GITHUB));
        githubTitle.getStyleClass().add("settings-card-title");
        Region githubSpacer = new Region();
        HBox.setHgrow(githubSpacer, Priority.ALWAYS);
        Label githubArrow = new Label(">");
        githubArrow.getStyleClass().add("settings-card-arrow");
        HBox ossRow = new HBox(10, githubIcon, ossText, githubSpacer, githubTitle, githubArrow);
        ossRow.setAlignment(Pos.CENTER_LEFT);

        Button githubCard = new Button();
        githubCard.setGraphic(ossRow);
        githubCard.setMaxWidth(Double.MAX_VALUE);
        githubCard.setAlignment(Pos.CENTER_LEFT);
        githubCard.getStyleClass().addAll("settings-link-card", "settings-quick-card", "settings-github-card");
        githubCard.setOnAction(e -> openInBrowser(GITHUB_URL));

        ImageView kofiIcon = new ImageView();
        var kofiStream = getClass().getResourceAsStream("/icons/kofi-icon.png");
        if (kofiStream != null) {
            Image icon = new Image(kofiStream, 22, 22, true, true);
            if (!icon.isError()) {
                kofiIcon.setImage(icon);
            }
        }
        kofiIcon.setFitWidth(22);
        kofiIcon.setFitHeight(22);
        kofiIcon.setPreserveRatio(true);
        kofiIcon.setSmooth(true);
        Label supportText = new Label(t(I18nKey.SETTINGS_SUPPORT_TEXT));
        supportText.getStyleClass().add("settings-label");
        supportText.setWrapText(false);
        Label donateTitle = new Label(t(I18nKey.SETTINGS_DONATE_BUTTON));
        donateTitle.getStyleClass().add("settings-card-title");
        Region donateSpacer = new Region();
        HBox.setHgrow(donateSpacer, Priority.ALWAYS);
        Label donateArrow = new Label(">");
        donateArrow.getStyleClass().add("settings-card-arrow");
        Label coffeeFallback = new Label("\u2615");
        coffeeFallback.getStyleClass().add("settings-icon-fallback");
        coffeeFallback.setVisible(kofiIcon.getImage() == null);
        coffeeFallback.setManaged(kofiIcon.getImage() == null);
        HBox supportRow = new HBox(10, kofiIcon, coffeeFallback, supportText, donateSpacer, donateTitle, donateArrow);
        HBox.setHgrow(supportText, Priority.ALWAYS);
        supportRow.setAlignment(Pos.CENTER_LEFT);

        Button donateCard = new Button();
        donateCard.setGraphic(supportRow);
        donateCard.setMaxWidth(Double.MAX_VALUE);
        donateCard.setAlignment(Pos.CENTER_LEFT);
        donateCard.getStyleClass().addAll("settings-link-card", "settings-quick-card", "settings-kofi-card");
        donateCard.setOnAction(e -> openInBrowser(KOFI_URL));

        VBox content = new VBox(
                10,
                perfLabel,
                smartRank,
                discordPresence,
                watchModeCheck,
                indexModeRow,
                incrementalStartupCheck,
                maxRow,
                languageRow,
                new Separator(),
                pluginLabel,
                reloadPluginsBtn,
                new Separator(),
                maintenanceLabel,
                themeSupportBtn,
                clearCacheBtn,
                clearStateBtn,
                new Separator(),
                githubCard,
                donateCard
        );
        content.getStyleClass().add("settings-content");
        ScrollPane contentScroll = new ScrollPane(content);
        contentScroll.setFitToWidth(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        contentScroll.getStyleClass().add("settings-scroll");

        Label title = new Label(t(I18nKey.UI_SETTINGS));
        title.getStyleClass().add("settings-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().addAll("window-btn", "window-btn-close");

        HBox header = new HBox(8, title, spacer, closeBtn);
        header.getStyleClass().add("settings-header");
        header.setAlignment(Pos.CENTER_LEFT);

        Button cancelBtn = new Button(t(I18nKey.SETTINGS_CANCEL));
        cancelBtn.getStyleClass().add("settings-btn");
        Button saveBtn = new Button(t(I18nKey.SETTINGS_SAVE));
        saveBtn.getStyleClass().addAll("settings-btn", "settings-btn-primary");
        HBox actions = new HBox(10, cancelBtn, saveBtn);
        actions.getStyleClass().add("settings-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);

        BorderPane window = new BorderPane();
        window.getStyleClass().add("settings-window");
        window.setTop(header);
        window.setCenter(contentScroll);
        window.setBottom(actions);

        Rectangle clip = new Rectangle();
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        clip.widthProperty().bind(window.widthProperty());
        clip.heightProperty().bind(window.heightProperty());
        window.setClip(clip);

        Stage modal = new Stage(StageStyle.TRANSPARENT);
        if (table != null && table.getScene() != null && table.getScene().getWindow() != null) {
            modal.initOwner(table.getScene().getWindow());
        }
        modal.initModality(Modality.WINDOW_MODAL);

        Scene scene = new Scene(window, 500, 520);
        scene.setFill(Color.TRANSPARENT);
        applyThemeToScene(scene);
        modal.setScene(scene);

        themeSupportBtn.setOnAction(e -> showThemeDialog(modal, scene));

        closeBtn.setOnAction(e -> modal.close());
        cancelBtn.setOnAction(e -> modal.close());
        saveBtn.setOnAction(e -> {
            indexService.setSmartRankingEnabled(smartRank.isSelected());
            appStateStore.saveSmartRankingEnabled(smartRank.isSelected());
            maxResults = maxResultsSpinner.getValue();
            appStateStore.saveMaxResults(maxResults);
            discordPresenceEnabled = discordPresence.isSelected();
            appStateStore.saveDiscordPresenceEnabled(discordPresenceEnabled);
            indexMode = indexModeSelector.getValue() == null ? IndexMode.AUTO : indexModeSelector.getValue();
            incrementalOnStartup = incrementalStartupCheck.isSelected();
            watchModeEnabled = watchModeCheck.isSelected();
            indexService.setIndexMode(indexMode);
            indexService.setIncrementalOnStartup(incrementalOnStartup);
            appStateStore.saveIndexMode(indexMode.name().toLowerCase(Locale.ROOT));
            appStateStore.saveIncrementalOnStartup(incrementalOnStartup);
            appStateStore.saveWatchModeEnabled(watchModeEnabled);
            LanguageOption selectedLanguage = languageSelector.getValue();
            appLanguageCode = selectedLanguage == null ? "en" : selectedLanguage.code();
            appStateStore.saveLanguage(appLanguageCode);
            applyLanguageToMainUi();
            if (watchModeEnabled) {
                startWatchMode();
            } else {
                stopWatchMode();
            }
            if (discordPresenceService != null) {
                discordPresenceService.setEnabled(discordPresenceEnabled);
                if (discordPresenceEnabled) {
                    discordPresenceService.start();
                    discordPresenceService.updateIdle(t(I18nKey.DISCORD_READY));
                } else {
                    discordPresenceService.stop();
                }
            }
            retriggerSearch();
            modal.close();
        });

        header.setOnMousePressed(e -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        header.setOnMouseDragged(e -> {
            modal.setX(e.getScreenX() - dragOffsetX);
            modal.setY(e.getScreenY() - dragOffsetY);
        });

        modal.showAndWait();
    }

    private void showThemeDialog(Stage owner, Scene settingsScene) {
        Label activeThemeLabel = new Label(t(I18nKey.SETTINGS_THEME_ACTIVE));
        activeThemeLabel.getStyleClass().add("settings-label");
        Label hint = new Label();
        hint.getStyleClass().add("settings-label");

        VBox themesList = new VBox(8);
        ToggleGroup themeGroup = new ToggleGroup();
        final ThemeOption[] selectedTheme = new ThemeOption[1];

        Runnable refreshThemes = () -> {
            List<ThemeOption> themes = scanThemes();
            themesList.getChildren().clear();
            themeGroup.getToggles().clear();
            selectedTheme[0] = null;

            String preferred = (selectedThemeFile != null && !selectedThemeFile.isBlank())
                    ? selectedThemeFile
                    : OFFICIAL_THEME_FILE;

            if (themes.isEmpty()) {
                hint.setText(t(I18nKey.SETTINGS_THEME_EMPTY));
                return;
            }

            hint.setText("");
            for (ThemeOption theme : themes) {
                boolean isActiveTheme = theme.fileName().equalsIgnoreCase(preferred);
                javafx.scene.control.RadioButton toggle = new javafx.scene.control.RadioButton();
                toggle.getStyleClass().add("theme-radio");
                toggle.setToggleGroup(themeGroup);
                toggle.setUserData(theme);

                String subText = theme.official()
                        ? (isActiveTheme ? t(I18nKey.SETTINGS_THEME_AUTO_ACTIVE) : "")
                        : theme.fileName();
                Label title = new Label(theme.displayName());
                title.getStyleClass().addAll("settings-card-title", "theme-card-title");
                Label authorLabel = new Label(t(I18nKey.SETTINGS_THEME_AUTHOR_PREFIX, theme.author()));
                authorLabel.getStyleClass().addAll("settings-label", "theme-card-author");
                String descriptionText = theme.description() == null || theme.description().isBlank()
                        ? subText
                        : t(I18nKey.SETTINGS_THEME_DESCRIPTION_PREFIX, theme.description());
                Label descriptionLabel = new Label(descriptionText);
                descriptionLabel.getStyleClass().addAll("settings-label", "theme-card-description");
                descriptionLabel.setWrapText(false);
                descriptionLabel.setMaxWidth(420);
                descriptionLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

                StackPane selectorWrap = new StackPane(toggle);
                selectorWrap.getStyleClass().add("theme-card-selector");
                selectorWrap.setPrefSize(30, 30);

                Region headSpacer = new Region();
                HBox.setHgrow(headSpacer, Priority.ALWAYS);
                HBox headRow = new HBox(10, title, headSpacer, selectorWrap);
                headRow.setAlignment(Pos.TOP_LEFT);
                headRow.getStyleClass().add("theme-card-head");

                VBox textCol = new VBox(4, headRow, authorLabel, descriptionLabel);
                textCol.getStyleClass().add("theme-card-text");
                HBox.setHgrow(textCol, Priority.ALWAYS);

                HBox card = new HBox(textCol);
                card.setMaxWidth(Double.MAX_VALUE);
                card.setAlignment(Pos.TOP_LEFT);
                card.getStyleClass().addAll("settings-link-card", "theme-card");
                card.setOnMouseClicked(evt -> toggle.setSelected(true));
                toggle.selectedProperty().addListener((obs, old, selected) -> {
                    if (selected) {
                        if (!card.getStyleClass().contains("settings-link-card-selected")) {
                            card.getStyleClass().add("settings-link-card-selected");
                        }
                    } else {
                        card.getStyleClass().remove("settings-link-card-selected");
                    }
                });

                themesList.getChildren().add(card);

                if (isActiveTheme) {
                    toggle.setSelected(true);
                    selectedTheme[0] = theme;
                }
            }

            if (selectedTheme[0] == null && !themeGroup.getToggles().isEmpty()) {
                themeGroup.selectToggle(themeGroup.getToggles().getFirst());
                selectedTheme[0] = (ThemeOption) themeGroup.getSelectedToggle().getUserData();
            }
        };
        refreshThemes.run();

        themeGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val != null) {
                selectedTheme[0] = (ThemeOption) val.getUserData();
            }
        });

        Button createThemeBtn = new Button(t(I18nKey.SETTINGS_THEME_CREATE_NEW));
        createThemeBtn.getStyleClass().add("settings-btn");
        createThemeBtn.setOnAction(e -> {
            try {
                Path created = createNewThemeFile();
                try {
                    openThemeInEditor(created);
                } catch (Exception ex) {
                    hint.setText(t(I18nKey.SETTINGS_THEME_OPEN_EDITOR_FAILED, ex.getMessage()));
                }
                refreshThemes.run();
            } catch (IOException ex) {
                hint.setText(t(I18nKey.SETTINGS_THEME_CREATE_FAILED, ex.getMessage()));
            }
        });

        Button openFolderBtn = new Button(t(I18nKey.SETTINGS_THEME_OPEN_FOLDER));
        openFolderBtn.getStyleClass().add("settings-btn");
        openFolderBtn.setOnAction(e -> {
            openThemesFolder(hint);
            refreshThemes.run();
        });

        Button refreshBtn = new Button(t(I18nKey.SETTINGS_THEME_REFRESH));
        refreshBtn.getStyleClass().add("settings-btn");
        refreshBtn.setOnAction(e -> refreshThemes.run());

        ScrollPane listScroll = new ScrollPane(themesList);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        listScroll.getStyleClass().add("settings-scroll");
        listScroll.setPrefViewportHeight(240);
        themesList.getStyleClass().add("theme-list");

        HBox toolsRow = new HBox(8, createThemeBtn, openFolderBtn, refreshBtn);
        toolsRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(
                10,
                activeThemeLabel,
                listScroll,
                hint,
                new Separator(),
                toolsRow
        );
        content.getStyleClass().add("settings-content");

        Label title = new Label(t(I18nKey.SETTINGS_THEME_DIALOG_TITLE));
        title.getStyleClass().add("settings-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().addAll("window-btn", "window-btn-close");
        HBox header = new HBox(8, title, spacer, closeBtn);
        header.getStyleClass().add("settings-header");
        header.setAlignment(Pos.CENTER_LEFT);

        Button cancelBtn = new Button(t(I18nKey.SETTINGS_CANCEL));
        cancelBtn.getStyleClass().add("settings-btn");
        Button saveBtn = new Button(t(I18nKey.SETTINGS_SAVE));
        saveBtn.getStyleClass().addAll("settings-btn", "settings-btn-primary");
        HBox actions = new HBox(10, cancelBtn, saveBtn);
        actions.getStyleClass().add("settings-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);

        BorderPane window = new BorderPane();
        window.getStyleClass().add("settings-window");
        window.setTop(header);
        window.setCenter(content);
        window.setBottom(actions);

        Rectangle clip = new Rectangle();
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        clip.widthProperty().bind(window.widthProperty());
        clip.heightProperty().bind(window.heightProperty());
        window.setClip(clip);

        Stage modal = new Stage(StageStyle.TRANSPARENT);
        modal.initOwner(owner);
        modal.initModality(Modality.WINDOW_MODAL);
        Scene themeScene = new Scene(window, 620, 520);
        themeScene.setFill(Color.TRANSPARENT);
        applyThemeToScene(themeScene);
        modal.setScene(themeScene);

        closeBtn.setOnAction(e -> modal.close());
        cancelBtn.setOnAction(e -> modal.close());
        saveBtn.setOnAction(e -> {
            ThemeOption selected = selectedTheme[0];
            if (selected == null) {
                selected = new ThemeOption(
                        OFFICIAL_THEME_FILE,
                        t(I18nKey.SETTINGS_THEME_DEFAULT_DARK_GLASS),
                        OFFICIAL_THEME_AUTHOR,
                        "",
                        true
                );
            }
            selectedThemeFile = selected.fileName();
            customThemeEnabled = !OFFICIAL_THEME_FILE.equalsIgnoreCase(selectedThemeFile);
            appStateStore.saveCustomThemeEnabled(customThemeEnabled);
            appStateStore.saveSelectedThemeFile(selectedThemeFile);
            applyThemeToMainScene();
            applyThemeToScene(settingsScene);
            applyThemeToScene(themeScene);
            modal.close();
        });

        modal.focusedProperty().addListener((obs, old, focused) -> {
            if (focused) {
                refreshThemes.run();
            }
        });

        header.setOnMousePressed(e -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        header.setOnMouseDragged(e -> {
            modal.setX(e.getScreenX() - dragOffsetX);
            modal.setY(e.getScreenY() - dragOffsetY);
        });

        modal.showAndWait();
    }

    private void openThemesFolder(Label statusTarget) {
        ensureThemesDir();
        try {
            if (!Desktop.isDesktopSupported()) {
                return;
            }
            Desktop.getDesktop().open(themesDir.toFile());
        } catch (Exception ex) {
            if (statusTarget != null) {
                statusTarget.setText(t(I18nKey.SETTINGS_THEME_FOLDER_OPEN_FAILED, ex.getMessage()));
            }
        }
    }

    private void openInBrowser(String url) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return;
            }
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
        }
    }

    private DiscordPresenceService createDiscordPresenceService() {
        try {
            return new DiscordPresenceService();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void applyEdgeSnap(Stage stage, double screenX, double screenY) {
        if (!isWindows()) {
            return;
        }
        Screen targetScreen = Screen.getScreensForRectangle(screenX, screenY, 1, 1)
                .stream()
                .findFirst()
                .orElse(Screen.getPrimary());
        Rectangle2D bounds = targetScreen.getVisualBounds();
        double threshold = 18.0;

        if (screenY <= bounds.getMinY() + threshold) {
            stage.setMaximized(true);
            return;
        }
        if (screenX <= bounds.getMinX() + threshold) {
            stage.setMaximized(false);
            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth() / 2.0);
            stage.setHeight(bounds.getHeight());
            return;
        }
        if (screenX >= bounds.getMaxX() - threshold) {
            stage.setMaximized(false);
            stage.setX(bounds.getMinX() + bounds.getWidth() / 2.0);
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth() / 2.0);
            stage.setHeight(bounds.getHeight());
        }
    }

    private Image loadAppIcon() {
        var stream = getClass().getResourceAsStream("/icons/app-logo.png");
        if (stream == null) {
            return null;
        }
        return new Image(stream);
    }

    private void setWindowsTaskbarIcon(Image fxImage) {
        try {
            if (!Taskbar.isTaskbarSupported()) {
                return;
            }
            Taskbar taskbar = Taskbar.getTaskbar();
            if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                return;
            }
            java.awt.Image awt = javafx.embed.swing.SwingFXUtils.fromFXImage(fxImage, null);
            taskbar.setIconImage(awt);
        } catch (Exception ignored) {
        }
    }
}
