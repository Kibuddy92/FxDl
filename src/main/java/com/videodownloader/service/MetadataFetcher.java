package com.videodownloader.service;

import com.google.gson.*;
import com.videodownloader.model.VideoFormat;
import javafx.concurrent.Task;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Background task: calls "yt-dlp --dump-single-json <url>"
 * and parses the result into a title + list of VideoFormats.
 * Optimized for heavy playlist payloads, streaming data safeguards, and cross-platform safety.
 */
public class MetadataFetcher extends Task<MetadataFetcher.Result> {

    public record Result(String title, List<VideoFormat> formats, boolean isPlaylist, int playlistCount) {}

    private final String url;
    private final boolean allowPlaylist;
    private volatile Process process;

    public MetadataFetcher(String url) {
        this(url, false);
    }

    /** Pass allowPlaylist=true when you want yt-dlp to report playlist metadata. */
    public MetadataFetcher(String url, boolean allowPlaylist) {
        this.url           = url;
        this.allowPlaylist = allowPlaylist;
    }

    @Override
    protected Result call() throws Exception {
        updateMessage("Contacting server…");

        List<String> cmd = new ArrayList<>(Arrays.asList(
            YtDlpManager.getBinaryPath(),
            "--dump-single-json",
            "--no-warnings"
        ));

        if (allowPlaylist) {
            cmd.add("--flat-playlist");
        } else {
            cmd.add("--no-playlist");
        }
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true); // Combines stderr into stdout stream safely
        process = pb.start();

        // Fix 2: Stream draining mechanism to prevent OS pipeline memory deadlocks
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream is = process.getInputStream()) {
            byte[] data = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(data, 0, data.length)) != -1) {
                if (isCancelled()) {
                    killProcess();
                    return null;
                }
                buffer.write(data, 0, bytesRead);
            }
        }

        int exit = process.waitFor();

        if (isCancelled()) {
            killProcess();
            return null;
        }

        // Fix 1: Enforce strict UTF-8 decoding bounds globally
        String json = buffer.toString(StandardCharsets.UTF_8);

        if (exit != 0 || json.isBlank()) {
            String errorSnippet = json.substring(0, Math.min(200, json.length())).trim();
            throw new Exception("Metadata engine extraction error (Exit code " + exit + "): " + errorSnippet);
        }

        return parse(json);
    }

    private Result parse(String raw) {
        JsonObject root   = JsonParser.parseString(raw).getAsJsonObject();
        String title      = str(root, "title", "Unknown Video");
        
        double duration = 0.0;
        if (root.has("duration") && !root.get("duration").isJsonNull()) {
            try {
                duration = root.get("duration").getAsDouble();
            } catch (Exception ignored) {}
        }

        // Detect playlist architecture markers
        boolean isPlaylist = (has(root, "_type") && "playlist".equals(root.get("_type").getAsString()))
                          || root.has("entries");
        int playlistCount  = 0;
        if (isPlaylist) {
            if (has(root, "playlist_count")) {
                playlistCount = root.get("playlist_count").getAsInt();
            } else if (root.has("entries") && root.get("entries").isJsonArray()) {
                playlistCount = root.getAsJsonArray("entries").size();
            }
        }

        List<VideoFormat> formats = new ArrayList<>();

        if (root.has("formats") && root.get("formats").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("formats");

            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject f = el.getAsJsonObject();

                double tbr = has(f, "tbr") ? f.get("tbr").getAsDouble() : 0.0;

                long size = 0L;
                if (has(f, "filesize"))             size = f.get("filesize").getAsLong();
                else if (has(f, "filesize_approx")) size = f.get("filesize_approx").getAsLong();
                else if (tbr > 0 && duration > 0)   size = (long)((tbr * 1000.0 / 8.0) * duration);

                String  id     = str(f, "format_id", "");
                String  ext    = str(f, "ext", "mp4");
                String  res    = str(f, "resolution", "");
                String  note   = str(f, "format_note", "");
                Integer height = has(f, "height") ? f.get("height").getAsInt() : 0;

                // Only register valid streaming channels
                if (height > 0 || tbr > 0) {
                    formats.add(new VideoFormat(id, ext, res, note, height, size, tbr, duration));
                }
            }
        }

        // Deduplicate profiles by video height, prioritizing the best bitrate (tbr)
        Map<Integer, VideoFormat> best = new LinkedHashMap<>();
        for (VideoFormat fmt : formats) {
            int h = fmt.getHeight();
            if (!best.containsKey(h) || fmt.getTbr() > best.get(h).getTbr()) {
                best.put(h, fmt);
            }
        }

        List<VideoFormat> deduped = new ArrayList<>(best.values());
        // Sort formats from highest resolution down to lowest resolution
        deduped.sort((a, b) -> b.getHeight().compareTo(a.getHeight()));

        return new Result(title, deduped, isPlaylist, playlistCount);
    }

    // Fix 3: Intercept Task cancellation signals to clean up the OS process tree
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        killProcess();
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected void cancelled() {
        super.cancelled();
        killProcess();
    }

    private synchronized void killProcess() {
        try {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (Exception ignored) {}
    }

    private static boolean has(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull();
    }

    private static String str(JsonObject o, String k, String def) {
        return has(o, k) ? o.get(k).getAsString() : def;
    }
}