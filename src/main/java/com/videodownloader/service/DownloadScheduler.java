package com.videodownloader.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Global queue scheduler — fires a user callback at configured times.
 * Schedules are persisted to JSON. The ticker wakes every 30 s.
 */
public class DownloadScheduler {

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static DownloadScheduler instance;

    public static synchronized DownloadScheduler getInstance() {
        if (instance == null) instance = new DownloadScheduler();
        return instance;
    }

    // ── Schedule Model ────────────────────────────────────────────────────────

    public static class Schedule {
        private String             id;
        private String             label;
        private String             timeStr;        // stored as "HH:mm"
        private List<String>       dayNames;       // stored as DayOfWeek.name()
        private boolean            enabled;
        private boolean            repeat;

        private transient boolean  firedThisMinute = false;

        public Schedule() {}

        public Schedule(String label, LocalTime time, EnumSet<DayOfWeek> days, boolean repeat) {
            this.id       = UUID.randomUUID().toString();
            this.label    = label;
            this.timeStr  = time.format(DateTimeFormatter.ofPattern("HH:mm"));
            this.dayNames = new ArrayList<>();
            if (days != null) days.forEach(d -> dayNames.add(d.name()));
            this.enabled  = true;
            this.repeat   = repeat;
        }

        // ── Accessors ──────────────────────────────────────────────────────────

        public String getId()      { return id; }
        public String getLabel()   { return label; }
        public void setLabel(String l) { label = l; }

        public LocalTime getTime() {
            if (timeStr == null) return LocalTime.MIDNIGHT;
            try { return LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm")); }
            catch (Exception e) { return LocalTime.MIDNIGHT; }
        }
        public void setTime(LocalTime t) {
            timeStr = t.format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        public EnumSet<DayOfWeek> getDays() {
            if (dayNames == null || dayNames.isEmpty()) return EnumSet.allOf(DayOfWeek.class);
            EnumSet<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
            for (String n : dayNames) {
                try { set.add(DayOfWeek.valueOf(n)); } catch (Exception ignored) {}
            }
            return set;
        }
        public void setDays(EnumSet<DayOfWeek> days) {
            dayNames = new ArrayList<>();
            if (days != null) days.forEach(d -> dayNames.add(d.name()));
        }

        public boolean isEnabled()      { return enabled; }
        public void setEnabled(boolean e) { enabled = e; }
        public boolean isRepeat()       { return repeat; }
        public void setRepeat(boolean r){ repeat = r; }

        /** Human-readable summary, e.g. "08:30 — Mon, Wed, Fri" */
        public String getSummary() {
            EnumSet<DayOfWeek> days = getDays();
            String timeDisplay = timeStr != null ? timeStr : "??:??";

            if (days.size() == 7) return timeDisplay + " — Every day";

            List<String> abbrev = new ArrayList<>();
            for (DayOfWeek d : DayOfWeek.values()) {
                if (days.contains(d)) {
                    String n = d.name();
                    abbrev.add(n.charAt(0) + n.substring(1, 3).toLowerCase());
                }
            }
            return timeDisplay + " — " + String.join(", ", abbrev);
        }

        @Override public String toString() { return getSummary(); }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private static final Path BASE_DIR = Paths.get(
        System.getProperty("user.home"), "Downloads", "FxVideo", ".config"
    );
    private static final Path FILE = BASE_DIR.resolve("schedules.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<Schedule>           schedules = new ArrayList<>();
    private final List<Consumer<Schedule>> listeners = new ArrayList<>();
    private ScheduledExecutorService       ticker;

    // ── Constructor ───────────────────────────────────────────────────────────

    private DownloadScheduler() {
        loadFromDisk();
        startTicker();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public synchronized List<Schedule> getSchedules() { return List.copyOf(schedules); }

    public synchronized void addSchedule(Schedule s) {
        schedules.add(s);
        saveToDisk();
    }

    public synchronized void removeSchedule(String id) {
        schedules.removeIf(s -> id.equals(s.getId()));
        saveToDisk();
    }

    public synchronized void updateSchedule(Schedule updated) {
        for (int i = 0; i < schedules.size(); i++) {
            if (schedules.get(i).getId().equals(updated.getId())) {
                schedules.set(i, updated);
                break;
            }
        }
        saveToDisk();
    }

    /** Register a listener — called on the JavaFX thread when a schedule fires. */
    public synchronized void addListener(Consumer<Schedule> listener) {
        listeners.add(listener);
    }

    public synchronized void removeListener(Consumer<Schedule> listener) {
        listeners.remove(listener);
    }

    // ── Ticker ────────────────────────────────────────────────────────────────

    private void startTicker() {
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-tick");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(this::tick, 0, 30, TimeUnit.SECONDS);
    }

    private synchronized void tick() {
        LocalDateTime now     = LocalDateTime.now();
        LocalTime     nowTime = now.toLocalTime().withSecond(0).withNano(0);
        DayOfWeek     today   = now.getDayOfWeek();

        for (Schedule s : schedules) {
            if (!s.enabled) { s.firedThisMinute = false; continue; }

            LocalTime trigger = s.getTime().withSecond(0).withNano(0);
            EnumSet<DayOfWeek> days = s.getDays();

            boolean timeMatch = trigger.equals(nowTime);
            boolean dayMatch  = days == null || days.isEmpty() || days.contains(today);

            if (timeMatch && dayMatch) {
                if (!s.firedThisMinute) {
                    s.firedThisMinute = true;
                    fire(s);
                    if (!s.repeat) {
                        s.enabled = false;
                        saveToDisk();
                    }
                }
            } else {
                s.firedThisMinute = false;
            }
        }
    }

    private void fire(Schedule s) {
        List<Consumer<Schedule>> snap;
        synchronized (this) { snap = List.copyOf(listeners); }
        javafx.application.Platform.runLater(() -> snap.forEach(l -> l.accept(s)));
    }

    // ── Disk I/O ──────────────────────────────────────────────────────────────

    private void loadFromDisk() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE)) {
            Type listType = new TypeToken<List<Schedule>>(){}.getType();
            List<Schedule> loaded = GSON.fromJson(r, listType);
            if (loaded != null) schedules.addAll(loaded);
        } catch (Exception e) {
            System.err.println("Could not load schedules: " + e.getMessage());
        }
    }

    public synchronized void saveToDisk() {
        try {
            Files.createDirectories(BASE_DIR);
            try (Writer w = Files.newBufferedWriter(FILE)) {
                GSON.toJson(schedules, w);
            }
        } catch (IOException e) {
            System.err.println("Could not save schedules: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (ticker != null) ticker.shutdownNow();
    }
}
