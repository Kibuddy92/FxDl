package com.videodownloader.util;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.videodownloader.model.VideoItem;
import com.videodownloader.model.VideoStatus;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles JSON persistence for the download manager queue.
 * Features atomic safe-saving mechanisms and cross-platform path resolution
 * to protect configuration state data from unexpected machine shutdowns or crashes.
 */
public class VideoPersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path BASE_DIR;
    private static final Path FILE;

    static {
        // Resolve target platform-specific system configuration paths cleanly
        String os = System.getProperty("os.name").toLowerCase();
        Path configRoot;

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isEmpty()) {
                configRoot = Paths.get(appData, "fxdl");
            } else {
                configRoot = Paths.get(System.getProperty("user.home"), ".config", "fxdl");
            }
        } else {
            // Standard Linux XDG Specification Compliance (~/.config/fxdl)
            String xdgConfig = System.getenv("XDG_CONFIG_HOME");
            if (xdgConfig != null && !xdgConfig.isEmpty()) {
                configRoot = Paths.get(xdgConfig, "fxdl");
            } else {
                configRoot = Paths.get(System.getProperty("user.home"), ".config", "fxdl");
            }
        }

        BASE_DIR = configRoot;
        FILE = BASE_DIR.resolve("downloads.json");
    }

    // Lightweight DTO for Gson
    private static class ItemData {
        String url, title, quality, size, filePath, formatId, status;
        double progress;
        long   totalSize;
    }

    public static void save(List<VideoItem> items) {
        try {
            Files.createDirectories(BASE_DIR);
            
            List<ItemData> list = items.stream().map(i -> {
                ItemData d    = new ItemData();
                d.url         = i.getUrl();
                d.title       = i.getTitle();
                d.quality     = i.getQuality();
                d.size        = i.getSize();
                d.filePath    = i.getFilePath();
                d.formatId    = i.getFormatId();
                d.totalSize   = i.getTotalSize();
                
                VideoStatus s = i.getStatus();

                if (s == VideoStatus.DOWNLOADING || s == VideoStatus.FETCHING
                || s == VideoStatus.MERGING     || s == VideoStatus.CONNECTING
                || s == VideoStatus.QUEUED) {
                    d.status   = VideoStatus.STOPPED.name();
                } else {
                    d.status   = s.name();
                }
                
                d.progress = i.getProgress() < 0 ? 0.0 : i.getProgress();
                return d;
            }).collect(Collectors.toList());

            // Atomic Safe-Write: Export metadata to a temp staging file first
            Path tempFile = BASE_DIR.resolve("downloads.json.tmp");
            
            // Explicitly force UTF-8 charset parameters across all platforms
            try (Writer w = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                GSON.toJson(list, w);
            }

            // Atomic file-swap eliminates data loss risks during runtime crashes
            try {
                Files.move(tempFile, FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tempFile, FILE, StandardCopyOption.REPLACE_EXISTING);
            }

        } catch (IOException e) {
            System.err.println("Could not save downloads dynamically: " + e.getMessage());
        }
    }

    public static List<VideoItem> load() {
        if (!Files.exists(FILE)) return new ArrayList<>();
        
        // Enforce explicit UTF-8 boundaries during file extraction passes
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            List<ItemData> list = GSON.fromJson(r, new TypeToken<List<ItemData>>(){}.getType());
            if (list == null) return new ArrayList<>();
            
            return list.stream()
                .filter(Objects::nonNull)
                .map(d -> {
                    VideoItem i = new VideoItem(
                        d.url, d.title, d.formatId, d.quality, d.totalSize, d.filePath
                    );
                    i.setSize(d.size != null ? d.size : "Unknown");
                    i.setProgress(d.progress);
                    
                    // ── STARTUP SANITIZATION ──
                    try {
                        VideoStatus loadedStatus = VideoStatus.valueOf(d.status);
                        
                        if (loadedStatus == VideoStatus.DOWNLOADING || loadedStatus == VideoStatus.FETCHING
                         || loadedStatus == VideoStatus.QUEUED      || loadedStatus == VideoStatus.MERGING
                         || loadedStatus == VideoStatus.CONNECTING) {
                            i.setStatus(VideoStatus.STOPPED);
                        } else {
                            i.setStatus(loadedStatus);
                        }
                    } catch (Exception e) {
                        i.setStatus(VideoStatus.STOPPED); 
                    }
                    
                    return i;
                }).collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Could not read configuration registry data: " + e.getMessage());
            return new ArrayList<>();
        }
    }
}