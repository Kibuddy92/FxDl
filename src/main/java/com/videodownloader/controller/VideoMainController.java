package com.videodownloader.controller;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.videodownloader.model.VideoFormat;
import com.videodownloader.model.VideoItem;
import com.videodownloader.model.VideoStatus;
import com.videodownloader.service.DownloadScheduler;
import com.videodownloader.service.MetadataFetcher;
import com.videodownloader.service.VideoDownloadTask;
import com.videodownloader.service.YtDlpManager;
import com.videodownloader.util.VideoPersistence;
import com.videodownloader.util.VideoSettings;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.util.Callback;

public class VideoMainController {

    // ── FXML ──────────────────────────────────────────────────────────────────
    @FXML private VBox    rootPane;
    @FXML private TextField urlField;
    @FXML private Label   ytdlpStatusLabel;

    @FXML private TableView<VideoItem> table;
    @FXML private TableColumn<?, Void>    iconCol;
    @FXML private TableColumn<VideoItem, String>    titleCol;
    @FXML private TableColumn<VideoItem, String>    qualityCol;
    @FXML private TableColumn<VideoItem, String>    sizeCol;
    @FXML private TableColumn<VideoItem, Double>    progressCol;
    @FXML private TableColumn<VideoItem, String>    speedCol;
    @FXML private TableColumn<VideoItem, String>    etaCol;
    @FXML private TableColumn<VideoItem, VideoStatus> statusCol;

    @FXML private Label activeLabel;
    @FXML private Label totalLabel;

    // ── State ─────────────────────────────────────────────────────────────────
    private final ObservableList<VideoItem> items = FXCollections.observableArrayList(
        item -> new javafx.beans.Observable[]{ item.statusProperty(), item.progressProperty() }
    );

    private final Map<VideoItem, VideoDownloadTask> activeTasks = new HashMap<>();
    private final Queue<VideoItem>                  queue       = new LinkedList<>();
    // Initialize the thread pool using your application settings configuration
    // private int maxConcurrent = AppSettings.getMaxThreads();
    private ExecutorService executor;

    private boolean darkMode = false;
    // Remembers the last format the user picked — reused for playlist downloads
    private String lastUsedFormatId = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best";
    // 💡 A dedicated sentinel instance to flag a playlist choice without altering your model rules
    // 💡 A dedicated sentinel instance to flag a playlist choice without altering your model rules
    private static final VideoFormat PLAYLIST_SENTINEL = new VideoFormat(
        "PLAYLIST_BATCH", "NONE", "0x0", "Playlist Batch Download", 0, 0, 0.0, 0.0
    );

    // ── Init ──────────────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        setupTable();
        setupContextMenu();
        loadFontAwesome7Solid(14);

        // Load persisted history
        items.addAll(VideoPersistence.load());

        table.setItems(items);

        // ===== THREAD POOL =====
        executor = Executors.newFixedThreadPool(
                VideoSettings.getMaxConcurrentDownloads(),
                r -> {
                    Thread t = new Thread(r, "video-dl-pool");
                    t.setDaemon(true);
                    return t;
                });

        // ===== LIVE STATUS TRACKING =====
        setupStatusTracking();

        // ===== yt-dlp STATUS =====
        Platform.runLater(this::refreshYtdlpStatus);

        // ===== INITIAL LABEL REFRESH =====
        refreshStatusBar();

        // ===== SCHEDULER =====
        setupScheduler();
    }

    private void setupScheduler() {
        DownloadScheduler.getInstance().addListener(schedule -> {
            // A schedule has fired — resume all stopped/failed items and process queue
            resumeAll();
            Platform.runLater(() -> {
                javafx.scene.control.Alert info = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.INFORMATION);
                info.setTitle("Scheduler");
                info.setHeaderText("Scheduled queue triggered");
                info.setContentText("\"" + schedule.getLabel() + "\" fired at "
                    + schedule.getSummary().split(" \u2014 ")[0]
                    + ". Queued downloads have been resumed.");
                if (rootPane != null && rootPane.getScene() != null)
                    info.getDialogPane().getStylesheets().addAll(rootPane.getScene().getStylesheets());
                info.getDialogPane().getStyleClass().add("video-dialog-pane");
                info.show();
            });
        });
    }

    private void setupStatusTracking() {
        // Track already-loaded items
        for (VideoItem item : items) {
            attachItemListeners(item);
        }

        // Track newly-added items
        items.addListener((javafx.collections.ListChangeListener<VideoItem>) change -> {

            while (change.next()) {

                if (change.wasAdded()) {

                    for (VideoItem item : change.getAddedSubList()) {
                        attachItemListeners(item);
                    }
                }
            }

            Platform.runLater(this::refreshStatusBar);
        });
    }

    private void attachItemListeners(VideoItem item) {

        item.statusProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(this::refreshStatusBar));

        item.progressProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(this::refreshStatusBar));

        item.speedProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(this::refreshStatusBar));
    }


    /**
     * Loads the Font Awesome 7 Solid font from the resources directory.
     * 
     * @param size The default size of the font to instantiate.
     * @return The loaded Font object, or null if loading failed.
     */
    public static Font loadFontAwesome7Solid(double size) {
        String fontPath = "/com/videodownloader/fonts/font-awesome-7-solid-900.otf";
        
        try (InputStream is = VideoDownloadTask.class.getResourceAsStream(fontPath)) {
            if (is == null) {
                System.err.println("Critical Error: Could not find font file at resources" + fontPath);
                return null;
            }
            
            Font loadedFont = Font.loadFont(is, size);
            if (loadedFont == null) {
                System.err.println("Font failed to load. The file might be corrupted or unreadable.");
                return null;
            }
            
            System.out.println("Successfully loaded font family: " + loadedFont.getFamily());
            return loadedFont;
            
        } catch (Exception e) {
            System.err.println("An error occurred while loading Font Awesome: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public void initTheme(boolean dark) {
        this.darkMode = dark;
    }

    // ── Table Setup ───────────────────────────────────────────────────────────

    private void setupTable() {
        titleCol.setCellValueFactory(d -> d.getValue().titleProperty());
        titleCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); return; }
                setText(s);
                setAlignment(Pos.CENTER_LEFT);
                setStyle("-fx-font-family: 'Consolas','Courier New',monospace;");
                setTooltip(new Tooltip(s));
            }
        });

        qualityCol.setCellValueFactory(d -> d.getValue().qualityProperty());
        qualityCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s);
                setAlignment(Pos.CENTER);
                setStyle("-fx-font-family: 'Consolas','Courier New',monospace;");
            }
        });

        sizeCol.setCellValueFactory(d -> d.getValue().sizeProperty());
        sizeCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); return; }
                setText(s);
                setStyle("-fx-font-family: 'Consolas','Courier New',monospace;");
                setAlignment(Pos.CENTER_LEFT);
            }
        });

        progressCol.setCellValueFactory(d -> d.getValue().progressProperty().asObject());
        progressCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(0);
            private final Label       pct = new Label("0%");
            private final javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(bar, pct);
            
            // Cache the last applied style class to avoid hitting the CSS engine unnecessarily
            private String lastAppliedStyleClass = "";

            {
                bar.setMaxWidth(Double.MAX_VALUE);
                pct.getStyleClass().add("progress-label");
                stack.setAlignment(Pos.CENTER);
            }

            @Override 
            protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                
                if (empty || v == null) { 
                    setGraphic(null); 
                    lastAppliedStyleClass = ""; // Reset cache
                    return; 
                }
                
                // 1. Safe Index Bounds Guard Check
                int index = getIndex();
                var tableItems = getTableView().getItems();
                if (index < 0 || index >= tableItems.size()) {
                    setGraphic(null);
                    return;
                }

                // 2. Safely extract your model state
                VideoItem item = tableItems.get(index);
                VideoStatus currentStatus = (item != null) ? item.getStatus() : null;

                // 3. Pass the VideoStatus enum directly into your method
                String targetStyleClass = statusBarClass(currentStatus);

                // 4. ONLY update CSS classes if the style class string actually changed!
                if (!targetStyleClass.equals(lastAppliedStyleClass)) {
                    bar.getStyleClass().removeIf(s -> s.startsWith("progress-bar-"));
                    bar.getStyleClass().add(targetStyleClass);
                    lastAppliedStyleClass = targetStyleClass; 
                }

                // 5. Normalization: Handle raw -1.0 values gracefully based on state
                double displayProgress = v;
                String displayText = "…";

         

                if (v < 0) {
                    // ONLY show an indeterminate animation if it's actively downloading or merging right now
                    if (currentStatus == VideoStatus.MERGING || currentStatus == VideoStatus.DOWNLOADING || currentStatus == VideoStatus.CONNECTING) {
                        displayProgress = -1.0; // Keep it indeterminate
                        displayText = (currentStatus == VideoStatus.MERGING) ? "Merging…" : "…";
                    } else {
                        // If it's paused, stopped, or failed, override the -1.0 to look resting/empty
                        displayProgress = 0.0;
                        displayText = "0%";
                    }
                } else {
                    displayText = String.format("%.0f%%", v * 100);
                }

                // 6. Smoothly set values using normalized variables
                bar.setProgress(displayProgress);
                pct.setText(displayText);
                
                setGraphic(stack);
            }
        });


        //
        speedCol.setCellValueFactory(d -> d.getValue().speedProperty());
        speedCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); return; }
                setText(s);
                setStyle("-fx-font-family: 'Consolas','Courier New',monospace;");
                setAlignment(Pos.CENTER_LEFT);
            }
        });

        etaCol.setCellValueFactory(d -> d.getValue().etaProperty());
        etaCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); return; }
                setText(s);
                setStyle("-fx-font-family: 'Consolas','Courier New',monospace;");
                setAlignment(Pos.CENTER);
            }
        });

        statusCol.setCellValueFactory(d -> d.getValue().statusProperty());
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(VideoStatus s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setText(null); setStyle(""); return; }
                setText(s.name().charAt(0) + s.name().substring(1).toLowerCase());
                setStyle("-fx-font-family: 'Consolas','Courier New',monospace;");
                setAlignment(Pos.CENTER_LEFT);
                setStyle(statusColor(s));
            }
        });

        setupIconColumn();
        table.setItems(items);
        table.setPlaceholder(new Label("No downloads yet — paste a URL above to start"));
    }


    private <T> void setupIconColumn() {
        // 1. Cast or explicitly type the column assignment context 
        @SuppressWarnings("unchecked")
        TableColumn<T, Void> typedIconCol = (TableColumn<T, Void>) iconCol;

        // 2. Define the Callback using the exact same explicit type parameter T
        typedIconCol.setCellFactory(new Callback<TableColumn<T, Void>, TableCell<T, Void>>() {
            @Override
            public TableCell<T, Void> call(TableColumn<T, Void> param) {
                return new TableCell<T, Void>() {
                    private final Label iconLabel = new Label("\uf008");
                    {
                        String fontName = "Font Awesome 7 Free Solid"; 
                        if (Font.getFamilies().contains(fontName)) {
                            iconLabel.setFont(Font.font(fontName, 16.0));
                        }
                        iconLabel.setStyle("-fx-text-fill: #5484ec;"); 
                    }

                    @Override
                    protected void updateItem(Void item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                            setGraphic(null);
                        } else {
                            setGraphic(iconLabel);
                        }
                    }
                };
            }
        });
    }


    private void setupContextMenu() {
        // ── Download control ──
    MenuItem miResume = new MenuItem("Resume");
    miResume.setGraphic(createMenuIcon("\uf04b", "icon-menu-green")); 

    MenuItem miStop   = new MenuItem("Stop");
    miStop.setGraphic(createMenuIcon("\uf04d", "icon-menu-red")); 

    // ── Queue management ──
    MenuItem miMoveTop    = new MenuItem("Move to Top");
    miMoveTop.setGraphic(createMenuIcon("\uf102", "icon-menu-blue")); 

    MenuItem miMoveUp     = new MenuItem("Move Up");
    miMoveUp.setGraphic(createMenuIcon("\uf077", "icon-menu-blue")); 

    MenuItem miMoveDown   = new MenuItem("Move Down");
    miMoveDown.setGraphic(createMenuIcon("\uf078", "icon-menu-blue")); 

    MenuItem miMoveBottom = new MenuItem("Move to Bottom");
    miMoveBottom.setGraphic(createMenuIcon("\uf103", "icon-menu-blue")); 

    // ── URL ──
    MenuItem miUpdateUrl = new MenuItem("Update URL…");
    miUpdateUrl.setGraphic(createMenuIcon("\uf01e", "icon-menu-orange")); 

    MenuItem miCopy      = new MenuItem("Copy URL");
    miCopy.setGraphic(createMenuIcon("\uf0c5", "icon-menu-gray")); 

    // ── File & removal ──
    MenuItem miFolder = new MenuItem("Open Folder");
    miFolder.setGraphic(createMenuIcon("\uf07c", "icon-menu-gray")); 

    MenuItem miRemove = new MenuItem("Remove from Queue");
    miRemove.setGraphic(createMenuIcon("\uf014", "icon-menu-orange")); 

    MenuItem miDelete = new MenuItem("Remove and Delete Files");
    miDelete.setGraphic(createMenuIcon("\uf2ed", "icon-menu-red"));
        ContextMenu cm = new ContextMenu();
        cm.getItems().addAll(
            miResume, miStop,
            new SeparatorMenuItem(),
            miMoveTop, miMoveUp, miMoveDown, miMoveBottom,
            new SeparatorMenuItem(),
            miUpdateUrl, miCopy,
            new SeparatorMenuItem(),
            miFolder,
            new SeparatorMenuItem(),
            miRemove, miDelete
        );

        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<VideoItem> row = new javafx.scene.control.TableRow<>();
            row.contextMenuProperty().bind(
                javafx.beans.binding.Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(cm)
            );
            row.setOnContextMenuRequested(e ->
                table.getSelectionModel().select(row.getItem())
            );
            return row;
        });

        // ── Disable/enable items dynamically before showing ───────────────────
        cm.setOnShowing(e -> {
            VideoItem item = table.getSelectionModel().getSelectedItem();
            if (item == null) {
                cm.getItems().forEach(i -> i.setDisable(true));
                return;
            }

            VideoStatus s = item.getStatus();
            boolean active     = s == VideoStatus.DOWNLOADING
                            || s == VideoStatus.FETCHING
                            || s == VideoStatus.QUEUED
                            || s == VideoStatus.MERGING;
            boolean resumable = s == VideoStatus.STOPPED
                            || s == VideoStatus.FAILED
                            || s == VideoStatus.CANCELLED;

            int idx   = items.indexOf(item);
            int last  = items.size() - 1;

            miStop.setDisable(!active);
            miResume.setDisable(!resumable);

            // Dynamic Text AND Graphic Icon handling for the dynamic state button
            // ── Inside your cm.setOnShowing listener, update the dynamic icon block:
            if (s == VideoStatus.FAILED || s == VideoStatus.CANCELLED) {
                miResume.setText("Retry");
                miResume.setGraphic(createMenuIcon("\uf01e", "icon-menu-green")); 
            } else {
                miResume.setText("Resume");
                miResume.setGraphic(createMenuIcon("\uf04b", "icon-menu-green")); 
            }

            miMoveTop.setDisable(idx <= 0);
            miMoveUp.setDisable(idx <= 0);
            miMoveDown.setDisable(idx >= last);
            miMoveBottom.setDisable(idx >= last);

            miUpdateUrl.setDisable(active);
            miRemove.setDisable(active);
            miDelete.setDisable(false);
            miCopy.setDisable(false);
            miFolder.setDisable(false);
        });

        // ── Action handlers ───────────────────────────────────────────────────
        miResume.setOnAction(e -> {
            VideoItem sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) resumeDownload(sel);
        });
        miStop.setOnAction(e -> {
            VideoItem sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) stopDownload(sel);
        });

        // Queue reordering
        miMoveTop.setOnAction(e    -> moveItem(table.getSelectionModel().getSelectedItem(), MoveDir.TOP));
        miMoveUp.setOnAction(e     -> moveItem(table.getSelectionModel().getSelectedItem(), MoveDir.UP));
        miMoveDown.setOnAction(e   -> moveItem(table.getSelectionModel().getSelectedItem(), MoveDir.DOWN));
        miMoveBottom.setOnAction(e -> moveItem(table.getSelectionModel().getSelectedItem(), MoveDir.BOTTOM));

        // Update URL
        miUpdateUrl.setOnAction(e -> {
            VideoItem sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showUpdateUrlDialog(sel);
        });

        miCopy.setOnAction(e -> {
            VideoItem sel = table.getSelectionModel().getSelectedItem();
            if (sel != null && sel.getUrl() != null) {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(sel.getUrl());
                Clipboard.getSystemClipboard().setContent(cc);
            }
        });
        miFolder.setOnAction(e -> {
            VideoItem sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openFolder(sel);
        });
        miRemove.setOnAction(e -> {
            VideoItem sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) deleteDownload(sel);
        });
        miDelete.setOnAction(e -> {
            VideoItem sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) removeAndDeleteFiles();
        });
    }

    /**
     * Helper to safely generate custom-styled context menu labels using our icon font.
     */
    private javafx.scene.Node createMenuIcon(String unicode, String colorClass) {
        Label iconLabel = new Label(unicode);
        
        // Add both the base icon class and the specific color class
        iconLabel.getStyleClass().addAll("context-menu-icon", colorClass);
        return iconLabel;
    }

    
    // ── Queue reorder helper ──────────────────────────────────────────────────

    private enum MoveDir { TOP, UP, DOWN, BOTTOM }

    private void moveItem(VideoItem item, MoveDir dir) {
        if (item == null) return;
        int idx = items.indexOf(item);
        if (idx < 0) return;
        int target = switch (dir) {
            case TOP    -> 0;
            case UP     -> Math.max(0, idx - 1);
            case DOWN   -> Math.min(items.size() - 1, idx + 1);
            case BOTTOM -> items.size() - 1;
        };
        if (target == idx) return;
        items.remove(idx);
        items.add(target, item);
        table.getSelectionModel().select(target);
        table.scrollTo(target);
    }

    // ── Update URL dialog ─────────────────────────────────────────────────────

    private void showUpdateUrlDialog(VideoItem item) {
        javafx.scene.control.Dialog<String> dlg = new javafx.scene.control.Dialog<>();
        dlg.setTitle("Update URL");
        dlg.setHeaderText("Enter a new URL for:\n" + item.getTitle());

        ButtonType okType = new ButtonType("Update", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        TextField urlInput = new TextField(item.getUrl());
        urlInput.setPrefWidth(480);
        urlInput.getStyleClass().add("video-url-field");

        // Enable Update button only when URL is non-empty and changed
        javafx.scene.Node okBtn = dlg.getDialogPane().lookupButton(okType);
        okBtn.setDisable(true);
        urlInput.textProperty().addListener((obs, o, n) ->
            okBtn.setDisable(n.isBlank() || n.equals(item.getUrl()))
        );

        VBox content = new VBox(8, new Label("New URL:"), urlInput);
        content.setPadding(new Insets(12, 0, 0, 0));
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().getStyleClass().add("video-dialog-pane");

        if (rootPane != null && rootPane.getScene() != null)
            dlg.getDialogPane().getStylesheets().addAll(rootPane.getScene().getStylesheets());

        dlg.setResultConverter(bt -> bt == okType ? urlInput.getText().trim() : null);

        Platform.runLater(urlInput::requestFocus);

        dlg.showAndWait().ifPresent(newUrl -> {
            if (!newUrl.isBlank()) {
                item.setUrl(newUrl);
                // Reset title so it re-fetches metadata on next resume
                item.setTitle("Fetching…");
                item.setStatus(VideoStatus.STOPPED);
                VideoPersistence.save(items);
            }
        });
    }

    // ── URL Bar ───────────────────────────────────────────────────────────────

    @FXML
    private void handleAddUrl() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;
        urlField.clear();
        startFetch(url);
    }

    @FXML
    private void handlePasteUrl() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasString()) urlField.setText(cb.getString());
    }

    //-------------------TOOLBAR BUTTONS ----------------------------------
   @FXML
    private void addVideoUrl() {
        // ── Phase 1: URL input dialog ─────────────────────────────────────────
        Dialog<String> inputDialog = new Dialog<>();
        inputDialog.setTitle("Add New Video Download");
        inputDialog.getDialogPane().getStylesheets().setAll(rootPane.getScene().getStylesheets());
        inputDialog.getDialogPane().getStyleClass().add("video-dialog-pane");

        Label titleLbl = new Label("Add Video URL");
        titleLbl.getStyleClass().add("video-dialog-title");

        Label subtitleLbl = new Label("Paste the link to the video you want to download:");
        subtitleLbl.getStyleClass().add("video-dialog-subtitle");
        subtitleLbl.setWrapText(true);

        TextField inputField = new TextField();
        inputField.setPromptText("https://www.youtube.com/watch?v=...");
        inputField.getStyleClass().add("video-url-field");
        inputField.setPrefHeight(40);

        Button pasteBtn = new Button("Paste");
        pasteBtn.getStyleClass().addAll("video-paste-btn");
        pasteBtn.setOnAction(e -> {
            Clipboard c = Clipboard.getSystemClipboard();
            if (c.hasString()) inputField.setText(c.getString().trim());
        });

        HBox urlRow = new HBox(0, inputField, pasteBtn);
        javafx.scene.layout.HBox.setHgrow(inputField, javafx.scene.layout.Priority.ALWAYS);
        urlRow.setAlignment(Pos.CENTER_LEFT);

        VBox inputContent = new VBox(12.0, titleLbl, subtitleLbl, urlRow);
        inputContent.getStyleClass().add("video-dialog-content");
        inputContent.setPadding(new Insets(18));
        inputContent.setPrefWidth(520);

        inputDialog.getDialogPane().setContent(inputContent);
        
        // Set sequential order (Cancel on Left, OK on Right)
        inputDialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        // FIX 1: Override OS default ButtonBar platform layouts sorting logic
        javafx.scene.control.ButtonBar buttonBar = (javafx.scene.control.ButtonBar) inputDialog.getDialogPane().lookup(".button-bar");
        if (buttonBar != null) {
            buttonBar.setButtonOrder(javafx.scene.control.ButtonBar.BUTTON_ORDER_NONE);
        }

        Button parseBtn = (Button) inputDialog.getDialogPane().lookupButton(ButtonType.OK);
        parseBtn.setText("Parse URL");
        parseBtn.getStyleClass().addAll("dialog-btn-primary", "settings-save-button"); // Bluish
        parseBtn.setDisable(true);

        Button cancelInputBtn = (Button) inputDialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelInputBtn.getStyleClass().addAll("dialog-btn-cancel", "settings-cancel-button"); // Reddish

        // Core value update synchronization listener hooks
        inputField.textProperty().addListener((obs, o, n) ->
            parseBtn.setDisable(n == null || n.trim().isEmpty()));

        inputField.setOnAction(e -> { if (!parseBtn.isDisable()) parseBtn.fire(); });

        // FIX 2: Run auto-paste via Platform.runLater to cleanly fire UI listeners
        javafx.application.Platform.runLater(() -> {
            Clipboard cb = Clipboard.getSystemClipboard();
            if (cb.hasString()) {
                String clip = cb.getString().trim();
                if (clip.startsWith("http")) {
                    inputField.setText(clip);
                    inputField.positionCaret(inputField.getText().length());
                }
            }
        });

        inputDialog.setResultConverter(bt ->
            bt == ButtonType.OK ? inputField.getText().trim() : null);

        String url = inputDialog.showAndWait().orElse(null);
        if (url == null || url.isBlank()) return;

        if (!isValidUrlSyntax(url)) {
            showErrorAlert("Invalid URL", "The text entered does not look like a valid URL.");
            return;
        }

        // ── Phase 2: Information Fetching Window Container ────────────────────
        Dialog<VideoFormat> qualityDialog = new Dialog<>();
        qualityDialog.setTitle("Fetching Video Info…");
        qualityDialog.getDialogPane().getStylesheets().setAll(rootPane.getScene().getStylesheets());
        qualityDialog.getDialogPane().getStyleClass().add("video-dialog-pane");
        
        // ... remaining implementation logic

        // -- Spinner Layout --
        javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator(-1);
        spinner.setPrefSize(48, 48);

        Label fetchingTitle = new Label("Fetching Video Info…");
        fetchingTitle.getStyleClass().add("video-dialog-title");

        Label fetchingStatus = new Label("Contacting server, please wait…");
        fetchingStatus.getStyleClass().add("video-dialog-subtitle");
        fetchingStatus.setWrapText(true);

        VBox spinnerPane = new VBox(16.0, fetchingTitle, spinner, fetchingStatus);
        spinnerPane.setAlignment(Pos.CENTER);
        spinnerPane.setPadding(new Insets(24, 32, 24, 32));
        spinnerPane.setPrefWidth(480);
        spinnerPane.setPrefHeight(160);

        // -- Quality Selection Layout --
        Label videoTitleLbl = new Label();
        videoTitleLbl.setWrapText(true);
        videoTitleLbl.getStyleClass().add("video-dialog-title");

        Label qualityCap = new Label("SELECT QUALITY");
        qualityCap.getStyleClass().add("dialog-section-cap");

        ComboBox<VideoFormat> combo = new ComboBox<>();
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.getStyleClass().add("video-quality-combo");

        Label saveCap = new Label("SAVE TO");
        saveCap.getStyleClass().add("dialog-section-cap");

        TextField pathField = new TextField(VideoSettings.getDownloadDir());
        pathField.setEditable(false);
        pathField.getStyleClass().add("dialog-path-field");

        DirectoryChooser dc = new DirectoryChooser();
        File initDir = new File(VideoSettings.getDownloadDir());
        if (!initDir.exists()) initDir.mkdirs();
        dc.setInitialDirectory(initDir);

        Button browse = new Button("Browse…");
        browse.getStyleClass().add("btn-browse");
        browse.setOnAction(e -> {
            File chosen = dc.showDialog(qualityDialog.getOwner());
            if (chosen != null) pathField.setText(chosen.getAbsolutePath());
        });

        HBox pathRow = new HBox(8, pathField, browse);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.HBox.setHgrow(pathField, javafx.scene.layout.Priority.ALWAYS);

        VBox qualityPane = new VBox(12.0, videoTitleLbl, qualityCap, combo, saveCap, pathRow);
        qualityPane.getStyleClass().add("video-dialog-content");
        qualityPane.setPadding(new Insets(18));
        qualityPane.setPrefWidth(480);
        qualityPane.setVisible(false);
        qualityPane.setManaged(false);

        // -- Multi-Option Playlist Choice Layout --
        Label playlistTitleLbl = new Label();
        playlistTitleLbl.setWrapText(true);
        playlistTitleLbl.getStyleClass().add("video-dialog-title");

        Label playlistSubLbl = new Label();
        playlistSubLbl.setWrapText(true);
        playlistSubLbl.getStyleClass().add("video-dialog-subtitle");

        Button btnDownloadAll = new Button("Download Entire Playlist");
        btnDownloadAll.getStyleClass().add("dialog-btn-primary");
        btnDownloadAll.setMaxWidth(Double.MAX_VALUE);

        Button btnDownloadSingleOnly = new Button("No, Just This Video Alone");
        btnDownloadSingleOnly.getStyleClass().add("btn-browse"); 
        btnDownloadSingleOnly.setMaxWidth(Double.MAX_VALUE);

        // 💡 FIX: Layout wrapper uses valid structural Node types & explicit floating parameters
        javafx.scene.control.Separator panelSeparator = new javafx.scene.control.Separator();
        panelSeparator.setPadding(new Insets(4, 0, 4, 0));

        VBox playlistPane = new VBox(14.0, playlistTitleLbl, playlistSubLbl, panelSeparator, btnDownloadAll, btnDownloadSingleOnly);
        playlistPane.getStyleClass().add("video-dialog-content");
        playlistPane.setPadding(new Insets(20));
        playlistPane.setPrefWidth(480);
        playlistPane.setVisible(false);
        playlistPane.setManaged(false);

        javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(spinnerPane, qualityPane, playlistPane);

        qualityDialog.getDialogPane().setContent(root);
        qualityDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button downloadBtn = (Button) qualityDialog.getDialogPane().lookupButton(ButtonType.OK);
        downloadBtn.setText("Download");
        downloadBtn.getStyleClass().add("dialog-btn-primary");
        downloadBtn.setDisable(true); 

        Button cancelBtn = (Button) qualityDialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelBtn.getStyleClass().add("dialog-btn-cancel");

        final boolean[] isPlaylistConfirmed = {false};
        final boolean[] fetchCancelled = {false};
        cancelBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> fetchCancelled[0] = true);

        // Wire choice button intercept listeners
        btnDownloadAll.setOnAction(e -> {
            isPlaylistConfirmed[0] = true;
            downloadBtn.setDisable(false);
            downloadBtn.fire(); 
        });

        btnDownloadSingleOnly.setOnAction(e -> {
            isPlaylistConfirmed[0] = false;
            
            // 💡 FIX: Swap back to loading view to pull explicit single video format data maps
            playlistPane.setVisible(false);   playlistPane.setManaged(false);
            spinnerPane.setVisible(true);     spinnerPane.setManaged(true);
            fetchingTitle.setText("Fetching Single Video Info…");
            fetchingStatus.setText("Extracting available quality streams…");
            qualityDialog.setTitle("Fetching Video Info…");
            qualityDialog.getDialogPane().getScene().getWindow().sizeToScene();

            // Target clean query strings safely omitting list indexes
            String singleVideoUrl = url;
            if (url.contains("&list=")) {
                singleVideoUrl = url.split("&list=")[0];
            }

            MetadataFetcher singleVideoFetcher = new MetadataFetcher(singleVideoUrl, false);

            singleVideoFetcher.setOnSucceeded(ev -> {
                if (fetchCancelled[0]) return;
                MetadataFetcher.Result singleResult = singleVideoFetcher.getValue();
                Platform.runLater(() -> {
                    if (singleResult.formats().isEmpty()) {
                        fetchingTitle.setText("No formats found");
                        fetchingStatus.setText("Could not extract downloadable streams for this specific item.");
                        spinner.setVisible(false);
                        return;
                    }

                    videoTitleLbl.setText(singleResult.title());
                    combo.getItems().setAll(singleResult.formats());
                    combo.getSelectionModel().selectFirst();

                    spinnerPane.setVisible(false);   spinnerPane.setManaged(false);
                    qualityPane.setVisible(true);     qualityPane.setManaged(true);
                    
                    qualityDialog.setTitle("Select Quality");
                    downloadBtn.setVisible(true);    downloadBtn.setDisable(false);
                    qualityDialog.getDialogPane().getScene().getWindow().sizeToScene();
                });
            });

            singleVideoFetcher.setOnFailed(ev -> {
                if (fetchCancelled[0]) return;
                Throwable errorEx = singleVideoFetcher.getException();
                Platform.runLater(() -> {
                    fetchingTitle.setText("Failed to fetch info");
                    fetchingStatus.setText(errorEx != null ? errorEx.getMessage() : "Unknown error");
                    spinner.setVisible(false);
                });
            });

            executor.submit(singleVideoFetcher);
        });

        qualityDialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                return isPlaylistConfirmed[0] ? PLAYLIST_SENTINEL : combo.getSelectionModel().getSelectedItem();
            }
            return null;
        });

        // Run original top-level metadata extraction profile
        MetadataFetcher fetcher = new MetadataFetcher(url, true);

        fetcher.setOnSucceeded(e -> {
            if (fetchCancelled[0]) return;
            MetadataFetcher.Result result = fetcher.getValue();
            Platform.runLater(() -> {

                // Playlist Route Intercept
                if (result.isPlaylist() && result.playlistCount() > 0) {
                    playlistTitleLbl.setText("\"" + result.title() + "\"");
                    playlistSubLbl.setText("This link is part of a playlist containing " + result.playlistCount() + " videos.\n\n"
                            + "How would you like to proceed?");

                    spinnerPane.setVisible(false);  spinnerPane.setManaged(false);
                    playlistPane.setVisible(true);   playlistPane.setManaged(true);

                    qualityDialog.setTitle("Playlist Detected");
                    downloadBtn.setVisible(false); 
                    qualityDialog.getDialogPane().getScene().getWindow().sizeToScene();
                    return;
                }

                // Normal Flow Fallback if a standalone link was dropped
                if (result.formats().isEmpty()) {
                    fetchingTitle.setText("No formats found");
                    fetchingStatus.setText("yt-dlp could not extract any downloadable streams from this URL.");
                    spinner.setVisible(false);
                    downloadBtn.setDisable(true);
                    return;
                }

                videoTitleLbl.setText(result.title());
                combo.getItems().setAll(result.formats());
                combo.getSelectionModel().selectFirst();

                spinnerPane.setVisible(false);  spinnerPane.setManaged(false);
                qualityPane.setVisible(true);   qualityPane.setManaged(true);

                qualityDialog.setTitle("Select Quality");
                downloadBtn.setDisable(false);
                qualityDialog.getDialogPane().getScene().getWindow().sizeToScene();
            });
        });

        fetcher.setOnFailed(e -> {
            if (fetchCancelled[0]) return;
            Throwable ex = fetcher.getException();
            Platform.runLater(() -> {
                fetchingTitle.setText("Failed to fetch info");
                fetchingStatus.setText(ex != null ? ex.getMessage() : "Unknown error");
                spinner.setVisible(false);
                downloadBtn.setDisable(true);
            });
        });

        fetcher.messageProperty().addListener((obs, o, n) ->
            Platform.runLater(() -> fetchingStatus.setText(n)));

        executor.submit(fetcher);

        Optional<VideoFormat> chosen = qualityDialog.showAndWait();
        if (fetchCancelled[0] || chosen.isEmpty()) return;

        // ── Phase 3: Routing Execution ───────────────────────────────────────
        VideoFormat fmt = chosen.get();

        if (fmt == PLAYLIST_SENTINEL && isPlaylistConfirmed[0]) {
            handlePlaylistDownload(url, fetcher.getValue().title(), fetcher.getValue().playlistCount());
        } else {
            String saveTo    = pathField.getText();
            String safeTitle = videoTitleLbl.getText().replaceAll("[\\\\/:*?\"<>|]", "_");
            String filePath  = saveTo + File.separator + safeTitle + ".mp4";

            lastUsedFormatId = fmt.getId() + "+bestaudio[ext=m4a]/best";

            VideoItem newItem = new VideoItem(
                url,
                videoTitleLbl.getText(),
                fmt.getId(),
                fmt.getQualityLabel(),
                fmt.getFilesize(),
                filePath
            );
            newItem.setSize(fmt.getFilesize() > 0 ? formatBytes(fmt.getFilesize()) : "Unknown");
            newItem.setStatus(VideoStatus.QUEUED);

            items.add(newItem);
            queueAndProcess(newItem);
            VideoPersistence.save(items);
            refreshStatusBar();
        }
    }


    @FXML
    private void resumeAll() {
        // Collect eligible items first to avoid any ConcurrentModificationException 
        // if items are re-ordered or manipulated during activation
        List<VideoItem> itemsToResume = items.stream()
            .filter(item -> item.getStatus() == VideoStatus.STOPPED || item.getStatus() == VideoStatus.FAILED)
            .toList();

        if (itemsToResume.isEmpty()) {
            return; 
        }

        for (VideoItem item : itemsToResume) {
            resumeDownload(item);
        }

        // Save the global batch state shift
        VideoPersistence.save(items);
        refreshStatusBar();
    }

    @FXML
    private void openVideosFolder() {
        // Grab your application's designated target folder directory path
        String downloadDirPath = VideoSettings.getDownloadDir();
        if (downloadDirPath == null || downloadDirPath.isBlank()) {
            showErrorAlert("Folder Error", "No download directory has been configured.");
            return;
        }

        File directory = new File(downloadDirPath);
        if (!directory.exists()) directory.mkdirs();

        executor.submit(() -> {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("explorer.exe", directory.getAbsolutePath()).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", directory.getAbsolutePath()).start();
                } else {
                    // Linux — xdg-open is the correct cross-DE launcher
                    new ProcessBuilder("xdg-open", directory.getAbsolutePath()).start();
                }
            } catch (IOException e) {
                Platform.runLater(() -> showErrorAlert("Cannot Open Folder",
                    "Failed to open the file explorer: " + e.getMessage()));
            }
        });
    }

    @FXML
    private void buyCoffee() {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                getClass().getResource("/com/videodownloader/fxml/settings_view.fxml")
            );
            javafx.scene.Parent root = loader.load();

            javafx.stage.Stage settingsStage = new javafx.stage.Stage();
            settingsStage.setTitle("Settings");
            settingsStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            settingsStage.initOwner(rootPane.getScene().getWindow()); 
            
            javafx.scene.Scene scene = new javafx.scene.Scene(root);
            scene.getStylesheets().addAll(rootPane.getScene().getStylesheets());
            settingsStage.setScene(scene);

            SettingsController controller = loader.getController();
            controller.setStage(settingsStage);
            
            // TARGET TAB 2: Force selection of the second tab (0-indexed layout rule)
            controller.selectTabByIndex(1);

            settingsStage.showAndWait(); 

            // Retain your post-close theme synchronization checks
            boolean currentDarkModeSetting = VideoSettings.isDarkMode();
            if (currentDarkModeSetting != darkMode) {
                applyTheme(currentDarkModeSetting);
                darkMode = currentDarkModeSetting; 
            }
            
        } catch (Exception e) {
            System.err.println("Could not launch settings view directly to the donation profile.");
            e.printStackTrace();
        }
    }

    @FXML
    private void handleExit() {
        shutdown(); // stop tasks and save first
        Platform.exit();
        System.exit(0);
    }

    @FXML
    private void openSettings() {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                getClass().getResource("/com/videodownloader/fxml/settings_view.fxml")
            );
            javafx.scene.Parent root = loader.load();

            // Instantiate new Stage Window wrapper container context
            javafx.stage.Stage settingsStage = new javafx.stage.Stage();
            settingsStage.setTitle("Settings");
            settingsStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            settingsStage.initOwner(rootPane.getScene().getWindow()); // centers to parent view
            
            javafx.scene.Scene scene = new javafx.scene.Scene(root);
            
            // Retain matching light/dark theme variables if you wish
            scene.getStylesheets().addAll(rootPane.getScene().getStylesheets());
            settingsStage.setScene(scene);

            // Pass Stage access context directly downstream into controller class pointer
            SettingsController controller = loader.getController();
            controller.setStage(settingsStage);

            settingsStage.showAndWait(); // Suspends main loop thread input until window close

            // Re-read settings after dialog closes and apply theme if it changed
            boolean currentDarkModeSetting = VideoSettings.isDarkMode();

            if (currentDarkModeSetting != darkMode) {
                applyTheme(currentDarkModeSetting);
                darkMode = currentDarkModeSetting; // keep your local controller boolean tracking state in sync
            }
            
        } catch (Exception e) {
            System.err.println("Could not load settings window pane structure layout profile.");
            e.printStackTrace();
        }
    }


    // ── Fetch metadata then show quality picker ───────────────────────────────

    /**
     * Queues a playlist download using the last-used format (or best available).
     * No quality prompt — yt-dlp handles format selection for all entries.
     */
    private void handlePlaylistDownload(String playlistUrl, String playlistTitle, int count) {

        // ── Step 1: Fetch formats from the first video only ───────────────────
        Dialog<VideoFormat> qualityDialog = new Dialog<>();
        qualityDialog.setTitle("Fetching Quality Options…");
        qualityDialog.getDialogPane().getStylesheets().setAll(rootPane.getScene().getStylesheets());
        qualityDialog.getDialogPane().getStyleClass().add("video-dialog-pane");

        // -- Spinner pane --
        javafx.scene.control.ProgressIndicator spinner =
            new javafx.scene.control.ProgressIndicator(-1);
        spinner.setPrefSize(44, 44);

        Label spinnerTitle = new Label("Fetching quality options…");
        spinnerTitle.getStyleClass().add("video-dialog-title");

        Label spinnerSub = new Label("Reading formats from the first video in the playlist…");
        spinnerSub.getStyleClass().add("video-dialog-subtitle");
        spinnerSub.setWrapText(true);

        VBox spinnerPane = new VBox(14, spinnerTitle, spinner, spinnerSub);
        spinnerPane.setAlignment(Pos.CENTER);
        spinnerPane.setPadding(new Insets(24, 32, 24, 32));
        spinnerPane.setPrefWidth(460);
        spinnerPane.setPrefHeight(150);

        // -- Quality pane --
        Label qualityTitle = new Label("Select Playlist Quality");
        qualityTitle.getStyleClass().add("video-dialog-title");

        Label qualitySub = new Label(
            "This quality will be applied to all " + count + " videos in the playlist.");
        qualitySub.getStyleClass().add("video-dialog-subtitle");
        qualitySub.setWrapText(true);

        Label qualityCap = new Label("SELECT QUALITY");
        qualityCap.getStyleClass().add("dialog-section-cap");

        ComboBox<VideoFormat> combo = new ComboBox<>();
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.getStyleClass().add("video-quality-combo");

        Label saveCap = new Label("SAVE TO");
        saveCap.getStyleClass().add("dialog-section-cap");

        TextField pathField = new TextField(VideoSettings.getDownloadDir());
        pathField.setEditable(false);
        pathField.getStyleClass().add("dialog-path-field");

        DirectoryChooser dc = new DirectoryChooser();
        File initDir = new File(VideoSettings.getDownloadDir());
        if (!initDir.exists()) initDir.mkdirs();
        dc.setInitialDirectory(initDir);

        Button browse = new Button("Browse…");
        browse.getStyleClass().add("btn-browse");
        browse.setOnAction(ev -> {
            File chosen = dc.showDialog(qualityDialog.getOwner());
            if (chosen != null) pathField.setText(chosen.getAbsolutePath());
        });

        HBox pathRow = new HBox(8, pathField, browse);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.HBox.setHgrow(pathField, javafx.scene.layout.Priority.ALWAYS);

        VBox qualityPane = new VBox(12, qualityTitle, qualitySub, qualityCap, combo, saveCap, pathRow);
        qualityPane.getStyleClass().add("video-dialog-content");
        qualityPane.setPadding(new Insets(18));
        qualityPane.setPrefWidth(460);
        qualityPane.setVisible(false);
        qualityPane.setManaged(false);

        javafx.scene.layout.StackPane dialogRoot =
            new javafx.scene.layout.StackPane(spinnerPane, qualityPane);

        qualityDialog.getDialogPane().setContent(dialogRoot);
        qualityDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button downloadBtn = (Button) qualityDialog.getDialogPane().lookupButton(ButtonType.OK);
        downloadBtn.setText("Download All");
        downloadBtn.getStyleClass().add("dialog-btn-primary");
        downloadBtn.setDisable(true);

        Button cancelBtn = (Button) qualityDialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelBtn.getStyleClass().add("dialog-btn-cancel");

        qualityDialog.setResultConverter(bt ->
            bt == ButtonType.OK ? combo.getSelectionModel().getSelectedItem() : null);

        // Fetch the first video's URL from the playlist, then its formats
        Thread firstVideoFetch = new Thread(() -> {
            try {
                // Get just the first entry URL cheaply
                ProcessBuilder pb = new ProcessBuilder(
                    YtDlpManager.getBinaryPath(),
                    "--flat-playlist",
                    "--dump-single-json",
                    "--playlist-items", "1",
                    "--no-warnings",
                    playlistUrl
                );
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String json = new String(proc.getInputStream().readAllBytes());
                proc.waitFor();

                com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();

                // Extract first entry URL
                String firstUrl = null;
                if (root.has("entries") && !root.get("entries").isJsonNull()) {
                    com.google.gson.JsonArray entries = root.getAsJsonArray("entries");
                    if (!entries.isEmpty()) {
                        com.google.gson.JsonObject first = entries.get(0).getAsJsonObject();
                        if (first.has("webpage_url") && !first.get("webpage_url").isJsonNull())
                            firstUrl = first.get("webpage_url").getAsString();
                        else if (first.has("url") && !first.get("url").isJsonNull())
                            firstUrl = first.get("url").getAsString();
                        else if (first.has("id") && !first.get("id").isJsonNull())
                            firstUrl = "https://www.youtube.com/watch?v="
                                    + first.get("id").getAsString();
                    }
                } else if (root.has("webpage_url") && !root.get("webpage_url").isJsonNull()) {
                    // Some extractors return the first video directly
                    firstUrl = root.get("webpage_url").getAsString();
                }

                if (firstUrl == null) throw new Exception("Could not resolve first video URL.");

                final String resolvedUrl = firstUrl;
                Platform.runLater(() ->
                    spinnerSub.setText("Fetching formats from first video…"));

                // Now fetch full metadata (formats) for that first video
                MetadataFetcher formatFetcher = new MetadataFetcher(resolvedUrl, false);
                formatFetcher.setOnSucceeded(ev -> {
                    MetadataFetcher.Result result = formatFetcher.getValue();
                    Platform.runLater(() -> {
                        if (result.formats().isEmpty()) {
                            spinnerTitle.setText("No formats found");
                            spinnerSub.setText("Could not extract formats from the first video.");
                            spinner.setVisible(false);
                            return;
                        }

                        combo.getItems().setAll(result.formats());

                        // Pre-select last used format if it matches one in the list
                        result.formats().stream()
                            .filter(f -> (f.getId() + "+bestaudio[ext=m4a]/best")
                                .equals(lastUsedFormatId))
                            .findFirst()
                            .ifPresentOrElse(
                                combo.getSelectionModel()::select,
                                combo.getSelectionModel()::selectFirst
                            );

                        spinnerPane.setVisible(false); spinnerPane.setManaged(false);
                        qualityPane.setVisible(true);  qualityPane.setManaged(true);
                        qualityDialog.setTitle("Select Playlist Quality");
                        downloadBtn.setDisable(false);
                        qualityDialog.getDialogPane().getScene().getWindow().sizeToScene();
                    });
                });
                formatFetcher.setOnFailed(ev -> Platform.runLater(() -> {
                    Throwable ex = formatFetcher.getException();
                    spinnerTitle.setText("Failed to fetch formats");
                    spinnerSub.setText(ex != null ? ex.getMessage() : "Unknown error");
                    spinner.setVisible(false);
                }));
                executor.submit(formatFetcher);

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    spinnerTitle.setText("Error");
                    spinnerSub.setText(ex.getMessage() != null ? ex.getMessage() : "Unknown error");
                    spinner.setVisible(false);
                });
            }
        }, "playlist-format-fetch");
        firstVideoFetch.setDaemon(true);
        firstVideoFetch.start();

        // Show dialog and wait for user to pick quality
        Optional<VideoFormat> chosenFormat = qualityDialog.showAndWait();
        if (chosenFormat.isEmpty()) return;

        VideoFormat fmt = chosenFormat.get();
        String formatId = fmt.getId() + "+bestaudio[ext=m4a]/best";
        lastUsedFormatId = formatId; // remember for next time

        String saveTo = pathField.getText();

        // ── Step 2: Fetch all entry URLs and create one VideoItem per video ────
        Dialog<Void> fetchingDialog = new Dialog<>();
        fetchingDialog.setTitle("Loading Playlist…");
        fetchingDialog.getDialogPane().getStylesheets().setAll(rootPane.getScene().getStylesheets());
        fetchingDialog.getDialogPane().getStyleClass().add("video-dialog-pane");

        javafx.scene.control.ProgressBar fetchBar =
            new javafx.scene.control.ProgressBar(-1);
        fetchBar.setPrefWidth(380);

        Label fetchLbl = new Label("Loading " + count + " playlist entries…");
        fetchLbl.getStyleClass().add("video-dialog-subtitle");

        VBox pContent = new VBox(12, fetchLbl, fetchBar);
        pContent.setPadding(new Insets(20));
        pContent.setAlignment(Pos.CENTER);
        pContent.setPrefWidth(440);

        fetchingDialog.getDialogPane().setContent(pContent);
        fetchingDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        ((Button) fetchingDialog.getDialogPane().lookupButton(ButtonType.CANCEL))
            .getStyleClass().add("dialog-btn-cancel");

        Thread fetchThread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    YtDlpManager.getBinaryPath(),
                    "--flat-playlist",
                    "--dump-single-json",
                    "--no-warnings",
                    playlistUrl
                );
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String json  = new String(proc.getInputStream().readAllBytes());
                proc.waitFor();

                com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();

                if (!root.has("entries") || root.get("entries").isJsonNull()) {
                    Platform.runLater(() -> {
                        fetchingDialog.close();
                        showErrorAlert("Playlist Error", "No entries found in playlist.");
                    });
                    return;
                }

                com.google.gson.JsonArray entries = root.getAsJsonArray("entries");
                String safePlaylistTitle = playlistTitle.replaceAll("[\\\\/:*?\"<>|]", "_");
                String playlistDir = saveTo + File.separator + safePlaylistTitle;
                new File(playlistDir).mkdirs();

                List<VideoItem> newItems = new ArrayList<>();
                int total = entries.size();

                for (int i = 0; i < total; i++) {
                    com.google.gson.JsonElement el = entries.get(i);
                    if (el.isJsonNull()) continue;
                    com.google.gson.JsonObject entry = el.getAsJsonObject();

                    String entryUrl = null;
                    if (entry.has("webpage_url") && !entry.get("webpage_url").isJsonNull())
                        entryUrl = entry.get("webpage_url").getAsString();
                    else if (entry.has("url") && !entry.get("url").isJsonNull())
                        entryUrl = entry.get("url").getAsString();
                    else if (entry.has("id") && !entry.get("id").isJsonNull())
                        entryUrl = "https://www.youtube.com/watch?v="
                                + entry.get("id").getAsString();

                    if (entryUrl == null) continue;

                    String entryTitle = (entry.has("title") && !entry.get("title").isJsonNull())
                        ? entry.get("title").getAsString() : "Video " + (i + 1);

                    String fileName = String.format("%03d - %s.mp4",
                        (i + 1), entryTitle.replaceAll("[\\\\/:*?\"<>|]", "_"));
                    String filePath = playlistDir + File.separator + fileName;

                    VideoItem item = new VideoItem(
                        entryUrl, entryTitle, formatId,
                        fmt.getQualityLabel(), 0, filePath
                    );
                    item.setStatus(VideoStatus.QUEUED);
                    item.setPlaylist(false);
                    newItems.add(item);

                    final int progress = i + 1;
                    Platform.runLater(() ->
                        fetchLbl.setText("Loaded " + progress + " of " + total + " entries…"));
                }

                Platform.runLater(() -> {
                    fetchingDialog.close();
                    if (newItems.isEmpty()) {
                        showErrorAlert("Playlist Error",
                            "Could not resolve any video URLs from playlist.");
                        return;
                    }
                    items.addAll(newItems);
                    newItems.forEach(this::queueAndProcess);
                    VideoPersistence.save(items);
                    refreshStatusBar();
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    fetchingDialog.close();
                    showErrorAlert("Playlist Error", ex.getMessage() != null
                        ? ex.getMessage() : "Unknown error fetching playlist.");
                });
            }
        }, "playlist-fetch");
        fetchThread.setDaemon(true);
        fetchThread.start();

        fetchingDialog.showAndWait();
    }


    private void startFetch(String url) {
        VideoItem placeholder = new VideoItem(url, "Fetching…", "", "-", 0,
            VideoSettings.getDownloadDir() + File.separator + "video.mp4");
        placeholder.setStatus(VideoStatus.FETCHING);
        items.add(placeholder);

        MetadataFetcher fetcher = new MetadataFetcher(url);
        fetcher.setOnSucceeded(e -> {
            MetadataFetcher.Result result = fetcher.getValue();
            Platform.runLater(() -> showQualityPicker(placeholder, result));
        });
        fetcher.setOnFailed(e -> Platform.runLater(() -> {
            placeholder.setStatus(VideoStatus.FAILED);
            placeholder.setTitle("Failed to fetch metadata");
            placeholder.setSpeed("Error");
            table.refresh();
        }));
        executor.submit(fetcher);
    }

    private void showQualityPicker(VideoItem placeholder, MetadataFetcher.Result result) {
        if (result.formats().isEmpty()) {
            placeholder.setStatus(VideoStatus.FAILED);
            placeholder.setTitle("No downloadable formats found");
            table.refresh();
            return;
        }

        // Build a simple dialog
        Dialog<VideoFormat> dialog = new Dialog<>();
        dialog.setTitle("Select Quality");
        dialog.getDialogPane().getStylesheets().setAll(rootPane.getScene().getStylesheets());
        dialog.getDialogPane().getStyleClass().add("video-dialog-pane");

        ComboBox<VideoFormat> combo = new ComboBox<>();
        combo.getItems().setAll(result.formats());
        combo.getSelectionModel().selectFirst();
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.getStyleClass().add("video-quality-combo");

        Label titleLbl = new Label(result.title());
        titleLbl.setWrapText(true);
        titleLbl.getStyleClass().add("video-dialog-title");

        Label capLbl = new Label("SELECT QUALITY");
        capLbl.getStyleClass().add("dialog-section-cap");

        DirectoryChooser dc = new DirectoryChooser();
        File initDir = new File(VideoSettings.getDownloadDir());
        if (!initDir.exists()) initDir.mkdirs();
        dc.setInitialDirectory(initDir);

        TextField pathField = new TextField(VideoSettings.getDownloadDir()
            + File.separator + "video");
        pathField.setEditable(false);
        pathField.getStyleClass().add("dialog-path-field");

        Button browse = new Button("Browse…");
        browse.getStyleClass().add("btn-browse");
        browse.setOnAction(e -> {
            File chosen = dc.showDialog(dialog.getOwner());
            if (chosen != null) pathField.setText(chosen.getAbsolutePath());
        });

        HBox pathRow = new HBox(8, pathField, browse);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.HBox.setHgrow(pathField, javafx.scene.layout.Priority.ALWAYS);

        Label saveCapLbl = new Label("SAVE TO");
        saveCapLbl.getStyleClass().add("dialog-section-cap");

        VBox content = new VBox(12, titleLbl, capLbl, combo, saveCapLbl, pathRow);
        content.getStyleClass().add("video-dialog-content");
        content.setPadding(new javafx.geometry.Insets(18));
        content.setPrefWidth(500);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setText("Download");
        okBtn.getStyleClass().add("dialog-btn-primary");
        Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelBtn.getStyleClass().add("dialog-btn-cancel");

        dialog.setResultConverter(bt -> bt == ButtonType.OK
            ? combo.getSelectionModel().getSelectedItem() : null);

        Optional<VideoFormat> chosen = dialog.showAndWait();

        if (chosen.isPresent()) {
            VideoFormat fmt = chosen.get();
            String saveTo   = pathField.getText();
            String title    = result.title().replaceAll("[\\\\/:*?\"<>|]", "_");
            String filePath = saveTo + File.separator + title + ".mp4";

            placeholder.setTitle(result.title());
            placeholder.setQuality(fmt.getQualityLabel());
            placeholder.setTotalSize(fmt.getFilesize());
            placeholder.setSize(fmt.getFilesize() > 0
                ? formatBytes(fmt.getFilesize()) : "Unknown");
            placeholder.setFilePath(filePath);
            placeholder.setFormatId(fmt.getId());
            placeholder.setStatus(VideoStatus.QUEUED);

            queueAndProcess(placeholder);
        } else {
            items.remove(placeholder);
        }

        VideoPersistence.save(items);
        refreshStatusBar();
    }

    // ── Queue / Download ──────────────────────────────────────────────────────

    private synchronized void queueAndProcess(VideoItem item) {
        item.setStatus(VideoStatus.QUEUED);
        item.setSpeed("Queued…");
        if (!queue.contains(item)) queue.add(item);
        processQueue();
    }

    private synchronized void processQueue() {
        int maxActive = VideoSettings.getMaxConcurrentDownloads();
        
        while (activeTasks.size() < maxActive && !queue.isEmpty()) {
            VideoItem next = queue.poll();
            
            if (next == null) continue;
            
            // 💡 THE BULLETPROOF SHIELD: If this flag is true, it was user-stopped. Skip it!
            if (next.isInterrupted() || next.getStatus() == VideoStatus.STOPPED) {
                System.out.println("Queue Manager caught interrupted item: " + next.getTitle() + ". Discarding.");
                continue;
            }
            
            if (next.getStatus() == VideoStatus.QUEUED) {
                launchTask(next);
            }
        }
        
        Platform.runLater(this::refreshStatusBar);
    }

    private void launchTask(VideoItem item) {
        // Drop the cross-thread shield completely right at launch
        item.setInterrupted(false);

        // 1. Instantiate the fresh worker task
        VideoDownloadTask downloadTask = new VideoDownloadTask(item);

        // 2. Map tracking control registration
        activeTasks.put(item, downloadTask);

        // 3. Listen for when the task ACTUALLY starts running out of the queue
        downloadTask.runningProperty().addListener((obs, wasRunning, isRunning) -> {
            if (isRunning) {
                Platform.runLater(() -> {
                    // If user stopped it while it was waiting to pull from executor pool, reject launch
                    if (item.isInterrupted()) return;
                    
                    item.setStatus(VideoStatus.CONNECTING);
                    item.setProgress(-1.0); // Show indeterminate spinner
                });
            }
        });

        // 4. Listen for actual progress updates
        downloadTask.progressProperty().addListener((obs, oldProgress, newProgress) -> {
            if (newProgress != null && !item.isInterrupted()) {
                double progressVal = newProgress.doubleValue();
                if (progressVal > 0.0) {
                    Platform.runLater(() -> {
                        if (!item.isInterrupted()) item.setProgress(progressVal);
                    });
                }
            }
        });

        // 5. Lifecycle hooks
        downloadTask.setOnSucceeded(e -> {
        activeTasks.remove(item);
        
        // 💡 THE FIX: If the user manually stopped it, do NOT let it say COMPLETED!
        if (item.isInterrupted()) {
            item.setStatus(VideoStatus.STOPPED);
            item.setSpeed("Stopped");
        } else {
            item.setStatus(VideoStatus.COMPLETED);
            item.setSpeed("Done");
        }
        
        cleanupAndNext(item);
    });

    downloadTask.setOnFailed(e -> {
        activeTasks.remove(item);
        
        // If it was interrupted, treat it as a clean stop instead of a system failure
        if (item.isInterrupted()) {
            item.setStatus(VideoStatus.STOPPED);
            item.setSpeed("Stopped");
        } else {
            item.setStatus(VideoStatus.FAILED);
            item.setSpeed("Failed");
        }
        
        cleanupAndNext(item);
    });

    downloadTask.setOnCancelled(e -> {
        activeTasks.remove(item);
        item.setStatus(VideoStatus.STOPPED);
        item.setSpeed("Stopped");
        cleanupAndNext(item);
    });

        // 6. Submit task to thread execution pool
        executor.submit(downloadTask);
    }

    private void cleanupAndNext(VideoItem item) {
        activeTasks.remove(item);
        Platform.runLater(() -> {
            VideoPersistence.save(items);
            table.refresh();
            refreshStatusBar();
            processQueue();
        });
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    private void stopDownload(VideoItem item) {
        System.out.println("Stopping download for: " + item.getTitle());
        
        // 1. Set the atomic token immediately
        item.setInterrupted(true); 
        item.setStatus(VideoStatus.STOPPED);
        item.setSpeed("Stopped");
        item.setEta("--:--:--");

        // 2. Safely evict from tracking collections
        queue.remove(item);
        VideoDownloadTask task = activeTasks.remove(item);
        
        if (task != null) {
            task.cancel(true); // Destroy the native OS process tree
        }
        
        Platform.runLater(() -> {
            table.refresh();
            refreshStatusBar();
            VideoPersistence.save(table.getItems());
        });
    }

    private void deleteDownload(VideoItem item) {
        stopDownload(item);
        items.remove(item);
        VideoPersistence.save(items);
        refreshStatusBar();
    }

    private void resumeDownload(VideoItem item) {
        if (item == null) return;

        // 1. Lower the cross-thread shield
        item.setInterrupted(false); 

        // 2. Clear residual stale bindings from previous runs
        item.progressProperty().unbind();

        // 3. Clean up empty/broken files if necessary
        File targetFile = new File(item.getFilePath());
        if (targetFile.exists()) {
            if (targetFile.length() == 0 || item.getStatus() == VideoStatus.FAILED) {
                targetFile.delete();
            }
        }

        // 4. Mark as QUEUED so processQueue can recognize it
        item.setStatus(VideoStatus.QUEUED);
        item.setSpeed("Queued…");
        item.setEta("--:--:--");
        // Note: Don't force setProgress(0.0) if you want to support yt-dlp partial download resuming!
        
        // 5. Add back to queue backlog list if missing
        if (!queue.contains(item)) {
            queue.add(item);
        }

        table.refresh();

        // 6. Kick off the queue process engine
        processQueue();
        
        // Save state update to disk
        VideoPersistence.save(table.getItems());
    }

    private void openFolder(VideoItem item) {
        try {
            File f = new File(item.getFilePath()).getParentFile();

            if (f == null || !f.exists()) {
                return;
            }

            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", f.getAbsolutePath()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", f.getAbsolutePath()).start();
            } else {
                // Linux / Unix environments
                try {
                    new ProcessBuilder("xdg-open", f.getAbsolutePath()).start();
                } catch (Exception ex) {
                    // Fallback to Java Desktop API if available
                    if (java.awt.Desktop.isDesktopSupported()) {
                        java.awt.Desktop.getDesktop().open(f);
                    } else {
                        throw ex;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Cannot open folder: " + e.getMessage());
        }
    }

    private void removeAndDeleteFiles() {
        // 1. Get the currently selected item from your TableView / SelectionModel
        // Replace 'downloadTable' with the actual name of your TableView control
        VideoItem selectedItem = table.getSelectionModel().getSelectedItem();

        if (selectedItem == null) {
            showErrorAlert("No Selection", "Please select a download task to remove and delete.");
            return;
        }

        // 2. Safeguard with a Confirmation Dialog
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Delete Confirmation");
        confirmAlert.setHeaderText("Delete File & Remove Task?");
        confirmAlert.setContentText("Are you sure you want to permanently delete the downloaded files for:\n\"" 
                + selectedItem.getTitle() + "\"?\n\nThis action cannot be undone.");

        // Style the confirmation alert to match your application theme
        if (rootPane != null && rootPane.getScene() != null) {
            confirmAlert.getDialogPane().getStylesheets().setAll(rootPane.getScene().getStylesheets());
            confirmAlert.getDialogPane().getStyleClass().add("video-dialog-pane");
            
            // Quick swap to put Cancel on Left and OK on Right if needed
            confirmAlert.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
            javafx.scene.control.ButtonBar buttonBar = (javafx.scene.control.ButtonBar) confirmAlert.getDialogPane().lookup(".button-bar");
            if (buttonBar != null) {
                buttonBar.setButtonOrder(javafx.scene.control.ButtonBar.BUTTON_ORDER_NONE);
            }
            
            Button okBtn = (Button) confirmAlert.getDialogPane().lookupButton(ButtonType.OK);
            if (okBtn != null) okBtn.getStyleClass().add("settings-save-button");
            Button cancelBtn = (Button) confirmAlert.getDialogPane().lookupButton(ButtonType.CANCEL);
            if (cancelBtn != null) cancelBtn.getStyleClass().add("settings-cancel-button");
        }

        java.util.Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            
            // 3. Clean up the background download process tree if it's currently active
            // (Assuming you track running tasks in a map or can query them)
            // e.g., activeTasksMap.get(selectedItem).cancel(true);

            // 4. Delete the physical files on a background thread to prevent UI stutter
            new Thread(() -> {
                boolean deletedMainFile = false;
                
                // Delete the main output file
                if (selectedItem.getFilePath() != null) {
                    File mainFile = new File(selectedItem.getFilePath());
                    if (mainFile.exists()) {
                        deletedMainFile = mainFile.delete();
                    }
                    
                    // Clean up any lingering hidden temporary directory associated with this download destination folder
                    File outDir = mainFile.getParentFile();
                    if (outDir != null && outDir.exists()) {
                        File tempDir = new File(outDir, ".temp_video");
                        if (tempDir.exists() && tempDir.isDirectory()) {
                            deleteDirectoryRecursively(tempDir);
                        }
                    }
                }

                // 5. Update the UI back on the JavaFX Application Thread
                Platform.runLater(() -> {
                    // Remove from the underlying ObservableList driving your TableView
                    // Replace 'taskList' with your actual ObservableList<VideoItem> instance
                    items.remove(selectedItem);
                    VideoPersistence.save(items);
                    // Optional toast notification / log update
                    System.out.println("Removed task and purged physical drive storage references.");
                });
            }).start();
        }
    }

    /**
     * Helper method to cleanly purge temporary directories and child segments.
     */
    private void deleteDirectoryRecursively(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectoryRecursively(file);
            }
        }
        directoryToBeDeleted.delete();
    }

    // ── Toolbar actions ───────────────────────────────────────────────────────
    @FXML
    public void handleStopAll() {
    System.out.println("🛑 [DEBUG] stopAll() invoked.");

    synchronized (this) {
        // 1. Instantly clear out the waiting queue backlog
        queue.clear(); 

        // 2. Loop continuously, pulling the FIRST available item out of the map 
        // until the map is completely empty. This avoids any iterator mutation bugs.
        while (!activeTasks.isEmpty()) {
            // Grab the next available key in the map
            VideoItem item = activeTasks.keySet().iterator().next();
            
            System.out.println("Stopping item via stopAll: " + item.getTitle());
            
            // 3. Directly call your working stopDownload logic for this item
            stopDownload(item);
        }
    }

    // 4. Force a clean batch update and save to disk
    Platform.runLater(() -> {
        table.refresh();
        refreshStatusBar();
        VideoPersistence.save(table.getItems());
        System.out.println("✅ stopAll complete. All active tasks terminated.");
    });
}

    @FXML
    private void handleClearCompleted() {
        // 1. Identify all items that are currently marked as COMPLETED
        // We collect them to a separate list first to avoid a ConcurrentModificationException
        List<VideoItem> completedItems = items.stream()
            .filter(item -> item.getStatus() == VideoStatus.COMPLETED)
            .toList();

        if (completedItems.isEmpty()) {
            return; // Nothing to clear!
        }

        // 2. Remove the completed items from your main FX collection
        items.removeAll(completedItems);

        // 3. Persist the updated, cleaned list state to disk
        VideoPersistence.save(items);

        // 4. Update your application footer/status bar metrics
        refreshStatusBar();
    }

    @FXML
    private void handleToggleTheme() {
        darkMode = !darkMode;
        VideoSettings.setDarkMode(darkMode);
        VideoSettings.save();

        var scene = rootPane.getScene();
        scene.getStylesheets().clear();
        String css = darkMode
            ? getClass().getResource("/com/videodownloader/css/video-dark.css").toExternalForm()
            : getClass().getResource("/com/videodownloader/css/video-light.css").toExternalForm();
        scene.getStylesheets().add(css);
    }

    @FXML
    private void handleChangeFolder() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Default Download Folder");
        
        String currentPath = VideoSettings.getDownloadDir();
        if (currentPath != null && !currentPath.isBlank()) {
            File current = new File(currentPath);
            // 💡 FIX: Ensure the path is an actual directory to prevent crashes
            if (current.exists() && current.isDirectory()) { 
                dc.setInitialDirectory(current);
            }
        }
        
        File chosen = dc.showDialog(rootPane.getScene().getWindow());
        if (chosen != null) {
            // 💡 FIX: Cleaned up the broken syntax typo here
            VideoSettings.setDownloadDir(chosen.getAbsolutePath());
            VideoSettings.save();
        }
    }


    @FXML
    private void handleClearAll() {
        // 💡 Prevent a blank dialog confirmation if the table is already completely empty
        if (table.getItems().isEmpty()) {
            return;
        }

        // Modern flat confirmation warning dialog setup
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Clear Table");
        alert.setHeaderText("Remove all downloads?");
        alert.setContentText("This will clear all items from the current view. Active downloads will be stopped.");

        // Apply your root styling sheet to keep the dialog dark/light theme consistent
        if (!rootPane.getStylesheets().isEmpty()) {
            alert.getDialogPane().getStylesheets().add(rootPane.getStylesheets().get(0));
        }
        alert.getDialogPane().getStyleClass().add("video-dialog-pane");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            
            // 1. Gracefully halt background threads first to stop progress cell updates
            handleStopAll(); 

            // 2. Break active row UI selections to reset layout focus loops
            table.getSelectionModel().clearSelection();

            // 3. Clear out the collection items bound to your TableView layout safely
            table.getItems().clear();
        }
    }


    @FXML
    private void handleOpenScheduler() {
        QueueSchedulerDialog dialog = new QueueSchedulerDialog();
        dialog.initOwner(table.getScene().getWindow());
        dialog.showAndWait();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void refreshYtdlpStatus() {
        if (ytdlpStatusLabel == null) return;

        // 1. Instantly show a safe loading status so the user knows an update is happening
        ytdlpStatusLabel.setText("Checking yt-dlp binary environment...");
        ytdlpStatusLabel.setStyle("-fx-text-fill: #7f8c8d;");

        // 2. Offload the heavy process checking onto a separate worker thread
        Thread workerThread = new Thread(() -> {
            // This runs safely in the background:
            boolean ok = YtDlpManager.isAvailable();
            String versionText = ok 
                ? "yt-dlp v" + YtDlpManager.getVersion() 
                : "yt-dlp not found — place it in bin/";

            // 3. Hand the final UI styling updates back to the primary JavaFX Thread context
            javafx.application.Platform.runLater(() -> {
                ytdlpStatusLabel.setText(versionText);
                ytdlpStatusLabel.setStyle(ok
                    ? "-fx-text-fill: #27ae60;"
                    : "-fx-text-fill: #e74c3c;");
            });
        });

        // Make the thread a daemon so it closes automatically if the user shuts down the app mid-check
        workerThread.setDaemon(true);
        workerThread.start();
    }

    // Apply Dark Mode
    public void applyTheme(boolean dark) {
        this.darkMode = dark;
        var scene = rootPane.getScene();
        if (scene == null) return;
        scene.getStylesheets().clear();
        String css = dark
            ? getClass().getResource("/com/videodownloader/css/video-dark.css").toExternalForm()
            : getClass().getResource("/com/videodownloader/css/video-light.css").toExternalForm();
        scene.getStylesheets().add(css);
    }

    private void refreshStatusBar() {

        if (activeLabel == null || totalLabel == null) {
            return;
        }

        long fetching = items.stream()
                .filter(i -> i.getStatus() == VideoStatus.FETCHING)
                .count();

        long queued = items.stream()
                .filter(i -> i.getStatus() == VideoStatus.QUEUED)
                .count();

        long downloading = items.stream()
                .filter(i ->
                        i.getStatus() == VideoStatus.DOWNLOADING
                    || i.getStatus() == VideoStatus.MERGING)
                .count();

        long completed = items.stream()
                .filter(i -> i.getStatus() == VideoStatus.COMPLETED)
                .count();

        long failed = items.stream()
                .filter(i ->
                        i.getStatus() == VideoStatus.FAILED
                    || i.getStatus() == VideoStatus.STOPPED
                    || i.getStatus() == VideoStatus.CANCELLED)
                .count();

        long active = fetching + queued + downloading;

        activeLabel.setText(
                "Active: " + active +
                "  (↓ " + downloading +
                " | Q " + queued +
                " | F " + fetching + ")"
        );

        totalLabel.setText(
                "Completed: " + completed +
                " | Failed: " + failed +
                " | Total: " + items.size()
        );
    }

    private static String statusBarClass(VideoStatus s) {
        return switch (s) {
            case QUEUED -> "progress-bar-queued";
            case CONNECTING -> "progress-bar-connecting";
            case DOWNLOADING -> "progress-bar-downloading";
            case MERGING     -> "progress-bar-merging";
            case COMPLETED   -> "progress-bar-completed";
            case FAILED, ERROR      -> "progress-bar-failed";
            case STOPPED     -> "progress-bar-stopped";
            default          -> "progress-bar-queued";
        };
    }

    private static String statusColor(VideoStatus s) {
        return switch (s) {
            // ⏳ Waiting in line: Neutral soft lavender/slate blue
            case QUEUED      -> "-fx-text-fill: #94a3b8;";
            
            // 🔗 Handshaking/Connecting: Bright clear cyan
            case CONNECTING  -> "-fx-text-fill: #06b6d4;";
            
            // 📥 Active data stream: Vibrant pipeline blue
            case DOWNLOADING -> "-fx-text-fill: #3b82f6;";
            
            // 🎬 FFmpeg audio/video processing: Indigo/Amethyst processing tone
            case MERGING     -> "-fx-text-fill: #818cf8;";
            
            // ✅ Success: Crisp, bright mint green 
            case COMPLETED   -> "-fx-text-fill: #10b981;";
            
            // ❌ Issues: High-visibility coral red
            case FAILED, ERROR -> "-fx-text-fill: #f43f5e;";
            
            // 🛑 Paused/Aborted state: Cool, readable medium gray
            case STOPPED, CANCELLED -> "-fx-text-fill: #8D6E63;";
            
            default          -> "-fx-text-fill: inherit;";
        };
    }

    private static String formatBytes(long b) {
        if (b <= 0) return "Unknown";
        String[] u = {"B","KB","MB","GB","TB"};
        int i = Math.min((int)(Math.log(b)/Math.log(1024)), u.length-1);
        return String.format("%.1f %s", b / Math.pow(1024, i), u[i]);
    }

   

    /**
     * Validates basic web URL syntax using a regex matcher.
     */
    private boolean isValidUrlSyntax(String url) {
        if (url == null || url.isEmpty()) return false;
        // Simple regex supporting http, https, and common video domain strings
        String urlRegex = "^(https?://)?(www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_\\+.~#?&//=]*)$";
        return Pattern.compile(urlRegex).matcher(url).matches();
    }

    /**
     * Displays a unified modal error alert dialogue block.
     */
    private void showErrorAlert(String title, String message) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(title);
        alert.setContentText(message);
        
        // Ensure the alert inherits your CSS styling theme configurations
        if (rootPane != null && rootPane.getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(rootPane.getScene().getStylesheets());
        }
        
        alert.showAndWait();
    }

    public void shutdown() {
        // 1. Stop all active downloads so their progress is final
        new ArrayList<>(activeTasks.keySet()).forEach(item -> {
            VideoDownloadTask task = activeTasks.get(item);
            if (task != null) task.cancel();
            // Set status to STOPPED so it persists correctly
            item.setStatus(VideoStatus.STOPPED);
            item.setSpeed("Stopped");
            item.setEta("--:--:--");
            // Guard negative progress (indeterminate/merging state)
            if (item.getProgress() < 0) item.setProgress(0.0);
        });
        activeTasks.clear();
        queue.clear();

        // 2. Save after all statuses and progress values are finalized
        VideoPersistence.save(items);
    }
}
