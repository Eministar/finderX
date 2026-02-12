package dev.eministar;

import dev.eministar.core.AppStateStore;
import dev.eministar.core.DiscordPresenceService;
import dev.eministar.core.FileRecord;
import dev.eministar.core.IndexProgress;
import dev.eministar.core.IndexService;
import dev.eministar.core.UpdateService;
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
import javafx.scene.control.Hyperlink;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
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
import javafx.util.Duration;

import java.awt.Desktop;
import java.awt.Taskbar;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class FinderXApp extends Application {
    private static final String APP_VERSION = "v1.0";
    private static final String RELEASE_API = "";
    private static final String GITHUB_URL = "https://github.com/eministar/FinderX";
    private static final double RESIZE_MARGIN = 7.0;

    private final IndexService indexService = new IndexService();
    private final UpdateService updateService = new UpdateService();
    private final SystemIconProvider iconProvider = new SystemIconProvider();
    private final AppStateStore appStateStore = new AppStateStore();
    private final DiscordPresenceService discordPresenceService;

    private final ObservableList<FileRecord> rows = FXCollections.observableArrayList();
    private final AtomicLong searchGeneration = new AtomicLong();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(60));

    private final LinkedHashSet<Path> pinnedPaths = new LinkedHashSet<>();
    private final List<Path> recentPaths = new ArrayList<>();

    private TableView<FileRecord> table;
    private Label statusLabel;
    private Label phaseLabel;
    private Label updateLabel;
    private ProgressBar progressBar;
    private TextField searchField;
    private ComboBox<String> driveSelector;
    private TextField extFilterField;
    private ToggleButton allFilterBtn;
    private ToggleButton filesFilterBtn;
    private ToggleButton foldersFilterBtn;
    private CheckBox recentOnlyCheck;
    private FlowPane quickAccessPane;

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

    private enum ResizeMode {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

    public FinderXApp() {
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
        scene.getStylesheets().add(getClass().getResource("/theme/dark-glass.css").toExternalForm());
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
            discordPresenceService.updateIdle("Ready to search");
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
        appStateStore.savePinned(pinnedPaths);
        appStateStore.saveRecent(recentPaths);
        appStateStore.saveSmartRankingEnabled(indexService.isSmartRankingEnabled());
        appStateStore.saveUsageScores(indexService.usageScoresSnapshot());
        appStateStore.saveSelectedRoot(activeRoot.toString());
        appStateStore.saveMaxResults(maxResults);
        appStateStore.saveDiscordPresenceEnabled(discordPresenceEnabled);
        if (discordPresenceService != null) {
            discordPresenceService.stop();
        }
        indexService.shutdown();
        iconProvider.shutdown();
    }

    private void loadState() {
        pinnedPaths.addAll(appStateStore.loadPinned());
        recentPaths.addAll(appStateStore.loadRecent());
        indexService.setUsageScores(appStateStore.loadUsageScores());
        indexService.setSmartRankingEnabled(appStateStore.loadSmartRankingEnabled());
        maxResults = Math.max(100, Math.min(5000, appStateStore.loadMaxResults()));
        discordPresenceEnabled = appStateStore.loadDiscordPresenceEnabled();
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
        Label appSub = new Label("Instant search");
        appSub.getStyleClass().add("app-subtitle");

        VBox appInfo = new VBox(0, appTitle, appSub);
        HBox brand = new HBox(10, logoView, appInfo);
        brand.setAlignment(Pos.CENTER_LEFT);

        updateLabel = new Label("Version " + APP_VERSION);
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
                discordPresenceService.updateIdle("Browsing " + selected);
            }
        });

        Button settingsBtn = new Button("Settings");
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
        searchField.setPromptText("Search files and folders...");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        allFilterBtn = new ToggleButton("All");
        filesFilterBtn = new ToggleButton("Files");
        foldersFilterBtn = new ToggleButton("Folders");
        ToggleGroup group = new ToggleGroup();
        allFilterBtn.setToggleGroup(group);
        filesFilterBtn.setToggleGroup(group);
        foldersFilterBtn.setToggleGroup(group);
        allFilterBtn.setSelected(true);

        allFilterBtn.getStyleClass().add("filter-chip");
        filesFilterBtn.getStyleClass().add("filter-chip");
        foldersFilterBtn.getStyleClass().add("filter-chip");

        extFilterField = new TextField();
        extFilterField.setPromptText("ext: pdf");
        extFilterField.getStyleClass().add("chip-input");
        extFilterField.setPrefWidth(120);

        recentOnlyCheck = new CheckBox("30d");
        recentOnlyCheck.getStyleClass().add("chip-check");

        CheckBox smartRankingCheck = new CheckBox("Smart rank");
        smartRankingCheck.setSelected(indexService.isSmartRankingEnabled());
        smartRankingCheck.getStyleClass().add("chip-check");
        smartRankingCheck.selectedProperty().addListener((obs, old, val) -> {
            indexService.setSmartRankingEnabled(val);
            appStateStore.saveSmartRankingEnabled(val);
            retriggerSearch();
        });

        HBox filterRow = new HBox(8, allFilterBtn, filesFilterBtn, foldersFilterBtn, extFilterField, recentOnlyCheck, smartRankingCheck);
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
                phaseLabel.setText("Ready");
                statusLabel.setText("Type to search");
                if (discordPresenceService != null) {
                    discordPresenceService.updateIdle("Ready to search");
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
        table.setPlaceholder(new Label("No results"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);

        TableColumn<FileRecord, String> nameCol = new TableColumn<>("Name");
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

        TableColumn<FileRecord, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().extension()));
        typeCol.setPrefWidth(120);

        TableColumn<FileRecord, String> pathCol = new TableColumn<>("Path");
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

        phaseLabel = new Label("Initializing");
        phaseLabel.getStyleClass().add("phase-label");

        statusLabel = new Label("Preparing index...");
        statusLabel.getStyleClass().add("status-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(12, phaseLabel, progressBar, spacer, statusLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 12, 10, 12));
        bar.getStyleClass().add("bottom-bar");
        return bar;
    }

    private ContextMenu buildContextMenu(TableRow<FileRecord> row) {
        MenuItem open = new MenuItem("Open");
        open.setOnAction(e -> {
            if (!row.isEmpty()) {
                openRecord(row.getItem());
            }
        });

        MenuItem openParent = new MenuItem("Open parent folder");
        openParent.setOnAction(e -> {
            if (!row.isEmpty() && row.getItem().parent() != null) {
                openPath(row.getItem().parent());
            }
        });

        MenuItem pinToggle = new MenuItem("Pin/Unpin");
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

        MenuItem copyPath = new MenuItem("Copy path");
        copyPath.setOnAction(e -> {
            if (!row.isEmpty()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(row.getItem().path().toString());
                Clipboard.getSystemClipboard().setContent(content);
            }
        });

        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> {
            if (row.isEmpty()) {
                return;
            }
            try {
                Files.deleteIfExists(row.getItem().path());
                rows.remove(row.getItem());
            } catch (IOException ex) {
                statusLabel.setText("Delete failed: " + ex.getMessage());
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
        statusLabel.setText("Path copied");
    }

    private void openParentFromSelection() {
        FileRecord selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.parent() == null) {
            return;
        }
        openPath(selected.parent());
    }

    private void startIndexing() {
        indexService.startIndex(activeRoot, this::onIndexProgress);
        rows.clear();
        statusLabel.setText("Type to search");
        if (discordPresenceService != null) {
            discordPresenceService.updateIndexing(activeRoot.toString(), 0);
        }
    }

    private void onIndexProgress(IndexProgress progress) {
        Platform.runLater(() -> {
            if (progress.running()) {
                statusLabel.setText("Indexing: " + progress.filesIndexed() + " files / " + progress.directoriesIndexed() + " folders");
                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                phaseLabel.setText(progress.phase());
                if (discordPresenceService != null) {
                    discordPresenceService.updateIndexing(activeRoot.toString(), progress.filesIndexed());
                }
            } else {
                statusLabel.setText(progress.currentPath());
                progressBar.setProgress(1.0);
                phaseLabel.setText("Ready");
                retriggerSearch();
                if (discordPresenceService != null) {
                    discordPresenceService.updateIdle("Ready to search");
                }
            }
        });
    }

    private void checkForUpdates() {
        updateService.checkLatestVersionAsync(RELEASE_API, APP_VERSION)
                .thenAccept(update -> Platform.runLater(() -> update.ifPresent(v -> updateLabel.setText("Update " + v + " available"))));
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
        phaseLabel.setText("Search");
        int limit = maxResults;
        indexService.searchAsync(query, limit).thenAccept(found -> Platform.runLater(() -> {
            if (gen != searchGeneration.get()) {
                return;
            }
            List<FileRecord> filtered = applyFilters(found);
            rows.setAll(filtered);
            statusLabel.setText(filtered.size() + " results");
            if (discordPresenceService != null) {
                discordPresenceService.updateSearch(query, filtered.size(), activeRoot.toString());
            }
        }));
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
            statusLabel.setText("Open failed: " + ex.getMessage());
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
            Button btn = quickAccessButton("Pinned", path);
            quickAccessPane.getChildren().add(btn);
        }

        int shownRecent = 0;
        for (Path path : recentPaths) {
            if (shownRecent >= 8) {
                break;
            }
            Button btn = quickAccessButton("Recent", path);
            quickAccessPane.getChildren().add(btn);
            shownRecent++;
        }
    }

    private Button quickAccessButton(String prefix, Path path) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        if (name.length() > 28) {
            name = name.substring(0, 27) + "...";
        }
        Button btn = new Button(prefix + ": " + name);
        btn.getStyleClass().add("quick-chip");
        btn.setOnAction(e -> openPath(path));
        return btn;
    }

    private void showSettingsDialog() {
        CheckBox smartRank = new CheckBox("Enable smart ranking");
        smartRank.setSelected(indexService.isSmartRankingEnabled());
        smartRank.getStyleClass().add("chip-check");
        CheckBox discordPresence = new CheckBox("Enable Discord Rich Presence");
        discordPresence.setSelected(discordPresenceEnabled);
        discordPresence.getStyleClass().add("chip-check");

        Spinner<Integer> maxResultsSpinner = new Spinner<>(100, 5000, maxResults, 100);
        maxResultsSpinner.setEditable(true);
        maxResultsSpinner.getStyleClass().add("settings-spinner");

        Button clearCacheBtn = new Button("Clear index cache");
        clearCacheBtn.getStyleClass().add("settings-btn");
        clearCacheBtn.setOnAction(e -> {
            indexService.clearIndexCache();
            statusLabel.setText("Index cache cleared");
        });

        Button clearStateBtn = new Button("Clear app state");
        clearStateBtn.getStyleClass().add("settings-btn");
        clearStateBtn.setOnAction(e -> {
            appStateStore.clearAllState();
            pinnedPaths.clear();
            recentPaths.clear();
            rebuildQuickAccess();
            statusLabel.setText("App state cleared");
        });

        Label perfLabel = new Label("Performance");
        perfLabel.getStyleClass().add("settings-section");
        Label maxLabel = new Label("Max results:");
        maxLabel.getStyleClass().add("settings-label");

        HBox maxRow = new HBox(10, maxLabel, maxResultsSpinner);
        maxRow.setAlignment(Pos.CENTER_LEFT);

        Label maintenanceLabel = new Label("Maintenance");
        maintenanceLabel.getStyleClass().add("settings-section");

        ImageView githubIcon = new ImageView();
        var ghStream = getClass().getResourceAsStream("/icons/github.png");
        if (ghStream != null) {
            githubIcon.setImage(new Image(ghStream));
        }
        githubIcon.setFitWidth(18);
        githubIcon.setFitHeight(18);
        Label ossText = new Label("FinderX is open source.");
        ossText.getStyleClass().add("settings-label");
        Hyperlink githubLink = new Hyperlink("GitHub");
        githubLink.getStyleClass().add("settings-link");
        githubLink.setOnAction(e -> openInBrowser(GITHUB_URL));
        HBox ossRow = new HBox(8, githubIcon, ossText, githubLink);
        ossRow.setAlignment(Pos.CENTER_LEFT);
        ossRow.getStyleClass().add("settings-oss");

        VBox content = new VBox(
                10,
                perfLabel,
                smartRank,
                discordPresence,
                maxRow,
                new Separator(),
                maintenanceLabel,
                clearCacheBtn,
                clearStateBtn,
                new Separator(),
                ossRow
        );
        content.getStyleClass().add("settings-content");

        Label title = new Label("Settings");
        title.getStyleClass().add("settings-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().addAll("window-btn", "window-btn-close");

        HBox header = new HBox(8, title, spacer, closeBtn);
        header.getStyleClass().add("settings-header");
        header.setAlignment(Pos.CENTER_LEFT);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("settings-btn");
        Button saveBtn = new Button("Save");
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
        if (table != null && table.getScene() != null && table.getScene().getWindow() != null) {
            modal.initOwner(table.getScene().getWindow());
        }
        modal.initModality(Modality.WINDOW_MODAL);

        Scene scene = new Scene(window, 400, 390);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/theme/dark-glass.css").toExternalForm());
        modal.setScene(scene);

        closeBtn.setOnAction(e -> modal.close());
        cancelBtn.setOnAction(e -> modal.close());
        saveBtn.setOnAction(e -> {
            indexService.setSmartRankingEnabled(smartRank.isSelected());
            appStateStore.saveSmartRankingEnabled(smartRank.isSelected());
            maxResults = maxResultsSpinner.getValue();
            appStateStore.saveMaxResults(maxResults);
            discordPresenceEnabled = discordPresence.isSelected();
            appStateStore.saveDiscordPresenceEnabled(discordPresenceEnabled);
            if (discordPresenceService != null) {
                discordPresenceService.setEnabled(discordPresenceEnabled);
                if (discordPresenceEnabled) {
                    discordPresenceService.start();
                    discordPresenceService.updateIdle("Ready to search");
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
