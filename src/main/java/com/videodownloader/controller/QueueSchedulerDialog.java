package com.videodownloader.controller;

import com.videodownloader.service.DownloadScheduler;
import com.videodownloader.service.DownloadScheduler.Schedule;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.*;
import javafx.stage.Modality;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full-featured scheduler dialog.
 *
 * Left panel  — list of saved schedules (toggle / delete)
 * Right panel — add/edit form: time picker, day checkboxes, label, repeat toggle
 */
public class QueueSchedulerDialog extends Dialog<Void> {

    private final DownloadScheduler scheduler = DownloadScheduler.getInstance();

    // ── Left panel widgets ────────────────────────────────────────────────────
    private final ObservableList<Schedule> scheduleItems =
        FXCollections.observableArrayList();
    private final ListView<Schedule> scheduleList = new ListView<>(scheduleItems);

    // ── Right panel widgets ───────────────────────────────────────────────────
    private final TextField         labelField   = new TextField();
    private final Spinner<Integer>  hourSpinner  = new Spinner<>(0, 23, 8, 1);
    private final Spinner<Integer>  minSpinner   = new Spinner<>(0, 59, 0, 1);
    private final CheckBox          repeatCheck  = new CheckBox("Repeat weekly");

    // Day-of-week checkboxes (Mon-first order)
    private final Map<DayOfWeek, CheckBox> dayBoxes = new LinkedHashMap<>();

    // Track which schedule is being edited (null = adding new)
    private Schedule editingSchedule = null;

    // ── Constructor ───────────────────────────────────────────────────────────

    public QueueSchedulerDialog() {
        setTitle("Download Queue Scheduler");
        initModality(Modality.APPLICATION_MODAL);
        setResizable(true);

        // Close button
        ButtonType closeType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().add(closeType);

        // Style the dialog pane to match app theme
        getDialogPane().getStyleClass().add("video-dialog-pane");

        // Build content
        SplitPane splitPane = buildContent();
        splitPane.setPrefSize(700, 420);

        getDialogPane().setContent(splitPane);
        setResultConverter(b -> null);

        // Load existing schedules
        refreshList();
    }

    // ── Layout Builder ────────────────────────────────────────────────────────

    private SplitPane buildContent() {
        SplitPane split = new SplitPane(buildLeftPanel(), buildRightPanel());
        split.setDividerPositions(0.42);
        return split;
    }

    /** LEFT: schedule list with toggle switch and delete button per row. */
    private VBox buildLeftPanel() {
        Label heading = new Label("Saved Schedules");
        heading.getStyleClass().add("video-dialog-title");

        scheduleList.setCellFactory(lv -> new ScheduleCell());
        scheduleList.setPrefHeight(360);
        VBox.setVgrow(scheduleList, Priority.ALWAYS);

        scheduleList.getSelectionModel().selectedItemProperty().addListener(
            (obs, old, sel) -> { if (sel != null) populateForm(sel); }
        );

        Label emptyLbl = new Label("No schedules yet.\nUse the form to add one.");
        emptyLbl.setStyle("-fx-text-fill: #999; -fx-text-alignment: center; -fx-font-size:12px;");
        emptyLbl.setAlignment(Pos.CENTER);
        scheduleList.setPlaceholder(emptyLbl);

        VBox pane = new VBox(12, heading, scheduleList);
        pane.setPadding(new Insets(16));
        pane.getStyleClass().add("video-dialog-content");
        return pane;
    }

    /** RIGHT: form to add or edit a schedule. */
    private VBox buildRightPanel() {
        Label heading = new Label("Add / Edit Schedule");
        heading.getStyleClass().add("video-dialog-title");

        // ── Label row ─────────────────────────────────────────────────────────
        Label labelCap = new Label("LABEL (optional)");
        labelCap.getStyleClass().add("dialog-section-cap");
        labelField.setPromptText("e.g. Morning batch");
        labelField.getStyleClass().add("video-url-field");

        // ── Time row ──────────────────────────────────────────────────────────
        Label timeCap = new Label("START TIME");
        timeCap.getStyleClass().add("dialog-section-cap");

        hourSpinner.setEditable(true);
        hourSpinner.setPrefWidth(72);
        hourSpinner.getValueFactory().setWrapAround(true);
        hourSpinner.getEditor().textProperty().addListener((obs, o, n) -> fixSpinner(hourSpinner, 0, 23));

        minSpinner.setEditable(true);
        minSpinner.setPrefWidth(72);
        minSpinner.getValueFactory().setWrapAround(true);
        minSpinner.getEditor().textProperty().addListener((obs, o, n) -> fixSpinner(minSpinner, 0, 59));

        Label colon = new Label(":");
        colon.setStyle("-fx-font-size:18px; -fx-font-weight:bold; -fx-padding:0 4;");

        HBox timeRow = new HBox(4, hourSpinner, colon, minSpinner);
        timeRow.setAlignment(Pos.CENTER_LEFT);

        // ── Days row ──────────────────────────────────────────────────────────
        Label daysCap = new Label("DAYS OF WEEK");
        daysCap.getStyleClass().add("dialog-section-cap");

        DayOfWeek[] orderedDays = {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        };
        String[] dayLabels = {"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};

        HBox daysRow = new HBox(8);
        daysRow.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < orderedDays.length; i++) {
            DayOfWeek d = orderedDays[i];
            CheckBox cb = new CheckBox(dayLabels[i]);
            cb.setSelected(true);
            dayBoxes.put(d, cb);
            daysRow.getChildren().add(cb);
        }

        // Quick-select buttons
        Button allDays  = new Button("All");
        Button weekdays = new Button("Weekdays");
        Button weekend  = new Button("Weekend");
        for (Button b : new Button[]{allDays, weekdays, weekend}) {
            b.getStyleClass().add("btn-browse");
        }
        allDays.setOnAction(e  -> dayBoxes.values().forEach(c -> c.setSelected(true)));
        weekdays.setOnAction(e -> dayBoxes.forEach((d, c) ->
            c.setSelected(d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY)));
        weekend.setOnAction(e  -> dayBoxes.forEach((d, c) ->
            c.setSelected(d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY)));

        HBox quickRow = new HBox(6, allDays, weekdays, weekend);
        quickRow.setAlignment(Pos.CENTER_LEFT);

        // ── Repeat ────────────────────────────────────────────────────────────
        repeatCheck.setSelected(true);
        repeatCheck.setStyle("-fx-font-size:13px;");

        // ── Action buttons ────────────────────────────────────────────────────
        Button saveBtn   = new Button("Save Schedule");
        Button deleteBtn = new Button("Delete");
        Button clearBtn  = new Button("Clear Form");

        saveBtn.getStyleClass().add("dialog-btn-primary");
        deleteBtn.getStyleClass().add("dialog-btn-cancel");
        deleteBtn.setDisable(true);
        clearBtn.getStyleClass().add("btn-browse");

        saveBtn.setOnAction(e   -> onSave(deleteBtn));
        deleteBtn.setOnAction(e -> onDelete());
        clearBtn.setOnAction(e  -> clearForm(deleteBtn));

        scheduleList.getSelectionModel().selectedItemProperty().addListener(
            (obs, old, sel) -> deleteBtn.setDisable(sel == null)
        );

        HBox btnRow = new HBox(8, saveBtn, deleteBtn, clearBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        // ── Assemble ─────────────────────────────────────────────────────────
        VBox pane = new VBox(10,
            heading,
            labelCap, labelField,
            timeCap, timeRow,
            daysCap, daysRow, quickRow,
            repeatCheck,
            new Separator(),
            btnRow
        );
        pane.setPadding(new Insets(16));
        pane.getStyleClass().add("video-dialog-content");
        return pane;
    }

    // ── Event Handlers ────────────────────────────────────────────────────────

    private void onSave(Button deleteBtn) {
        EnumSet<DayOfWeek> selected = EnumSet.noneOf(DayOfWeek.class);
        dayBoxes.forEach((d, c) -> { if (c.isSelected()) selected.add(d); });
        if (selected.isEmpty()) selected.addAll(EnumSet.allOf(DayOfWeek.class));

        LocalTime time = LocalTime.of(
            clamp(hourSpinner.getValue(), 0, 23),
            clamp(minSpinner.getValue(), 0, 59)
        );

        String lbl = labelField.getText().trim();
        if (lbl.isEmpty()) lbl = "Download at " + time;

        if (editingSchedule == null) {
            Schedule s = new Schedule(lbl, time, selected, repeatCheck.isSelected());
            scheduler.addSchedule(s);
        } else {
            editingSchedule.setLabel(lbl);
            editingSchedule.setTime(time);
            editingSchedule.setDays(selected);
            editingSchedule.setRepeat(repeatCheck.isSelected());
            scheduler.updateSchedule(editingSchedule);
        }

        clearForm(deleteBtn);
        refreshList();
    }

    private void onDelete() {
        Schedule sel = scheduleList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        scheduler.removeSchedule(sel.getId());
        editingSchedule = null;
        refreshList();
    }

    private void populateForm(Schedule s) {
        editingSchedule = s;
        labelField.setText(s.getLabel() != null ? s.getLabel() : "");
        LocalTime t = s.getTime();
        hourSpinner.getValueFactory().setValue(t.getHour());
        minSpinner.getValueFactory().setValue(t.getMinute());
        EnumSet<DayOfWeek> days = s.getDays();
        dayBoxes.forEach((d, cb) -> cb.setSelected(days.contains(d)));
        repeatCheck.setSelected(s.isRepeat());
    }

    private void clearForm(Button deleteBtn) {
        editingSchedule = null;
        labelField.clear();
        hourSpinner.getValueFactory().setValue(8);
        minSpinner.getValueFactory().setValue(0);
        dayBoxes.values().forEach(c -> c.setSelected(true));
        repeatCheck.setSelected(true);
        scheduleList.getSelectionModel().clearSelection();
        deleteBtn.setDisable(true);
    }

    private void refreshList() {
        scheduleItems.setAll(scheduler.getSchedules());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void fixSpinner(Spinner<Integer> s, int lo, int hi) {
        try {
            int v = Integer.parseInt(s.getEditor().getText().trim());
            if (v < lo) v = lo;
            if (v > hi) v = hi;
            s.getValueFactory().setValue(v);
        } catch (NumberFormatException ignored) {}
    }

    // ── List Cell ─────────────────────────────────────────────────────────────

    private class ScheduleCell extends ListCell<Schedule> {
        private final Label      summaryLbl  = new Label();
        private final Label      labelLbl    = new Label();
        private final CheckBox   enabledBox  = new CheckBox();
        private final Button     deleteBtn   = new Button("\u2715");
        private final HBox       row         = new HBox(10);

        ScheduleCell() {
            summaryLbl.setStyle("-fx-font-family:'Consolas','Courier New',monospace; -fx-font-size:12px;");
            labelLbl.setStyle("-fx-font-size:11px; -fx-text-fill:#888;");
            deleteBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:#e74c3c; -fx-cursor:hand; -fx-font-weight:bold; -fx-padding:0 4;");
            deleteBtn.setTooltip(new Tooltip("Delete this schedule"));

            VBox text = new VBox(2, summaryLbl, labelLbl);
            text.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(text, Priority.ALWAYS);

            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 6, 4, 6));
            row.getChildren().addAll(enabledBox, text, deleteBtn);

            enabledBox.selectedProperty().addListener((obs, old, nv) -> {
                Schedule s = getItem();
                if (s == null) return;
                s.setEnabled(nv);
                scheduler.updateSchedule(s);
                updateStyle(nv);
            });

            deleteBtn.setOnAction(e -> {
                Schedule s = getItem();
                if (s != null) {
                    scheduler.removeSchedule(s.getId());
                    if (editingSchedule != null && editingSchedule.getId().equals(s.getId()))
                        editingSchedule = null;
                    refreshList();
                }
            });
        }

        @Override
        protected void updateItem(Schedule s, boolean empty) {
            super.updateItem(s, empty);
            if (empty || s == null) { setGraphic(null); return; }
            summaryLbl.setText(s.getSummary());
            labelLbl.setText(s.getLabel() != null && !s.getLabel().isBlank() ? s.getLabel() : "");
            labelLbl.setVisible(!labelLbl.getText().isEmpty());
            enabledBox.setSelected(s.isEnabled());
            updateStyle(s.isEnabled());
            setGraphic(row);
        }

        private void updateStyle(boolean enabled) {
            summaryLbl.setOpacity(enabled ? 1.0 : 0.45);
            labelLbl.setOpacity(enabled ? 1.0 : 0.45);
        }
    }
}
