package com.videodownloader.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.videodownloader.model.VideoItem;
import com.videodownloader.model.VideoStatus;
import com.videodownloader.util.Toast;

import javafx.application.Platform;
import javafx.concurrent.Task;

/**
 * Handles the background download process using yt-dlp.
 * Optimized for multi-threaded UI updates, custom playlist logic,
 * and robust cross-platform asset path resolution (Windows & Linux .deb builds).
 */
public class VideoDownloadTask extends Task<Void> {

    private static final Pattern PROGRESS_RE = Pattern.compile(
        "\\[PROGRESS\\]\\s*(\\d+\\.?\\d*)%\\|([^|]*)\\|([^|]*)\\|(.*)"
    );
    
    // Configured strictly to one decimal place for crisp UI telemetry tracking
    private static final DecimalFormat DF = new DecimalFormat("#,##0.0");
    private static final long UI_UPDATE_THROTTLE_MS = 100;

    private final VideoItem    item;
    private volatile Process   process;
    private boolean            isMerging        = false;
    private final AtomicLong   lastUiUpdateTime = new AtomicLong(0);
    private long               totalSize;

    public VideoDownloadTask(VideoItem item) {
        this.item      = item;
        this.totalSize = item.getTotalSize();
    }

    @Override
    protected Void call() throws Exception {
        setStatus(VideoStatus.CONNECTING);

        // ── Phase 1: Pre-download Metadata Lookup ──
        if (this.totalSize <= 0 || "Unknown".equalsIgnoreCase(item.getSize())) {
            long fetchedSize = fetchSizeSequentially();
            if (fetchedSize > 0) {
                this.totalSize = fetchedSize;
                Platform.runLater(() -> {
                    item.setTotalSize(fetchedSize);
                    item.setSize(formatBytes(fetchedSize));
                });
            }
            if (isCancelled()) return null;
        }

        File outFile = new File(item.getFilePath());
        File outDir  = outFile.getParentFile();
        if (outDir != null && !outDir.exists()) outDir.mkdirs();

        // Native hidden geometry tracking (leading dot hides natively on Unix/Linux)
        File tempDir = new File(outDir, ".temp_video");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }

        // ── Phase 2: Dynamic Process Argument Array Building ──
        List<String> args = new ArrayList<>();
        args.add(YtDlpManager.getBinaryPath());
        args.add("-N");
        args.add(item.isPlaylist() ? "4" : "2");
        args.add("--buffer-size"); 
        args.add("16K");
        args.add("--no-mtime");

        if (item.isPlaylist()) {
            args.add("--yes-playlist");
        } else {
            args.add("--no-playlist");
        }

        // Safety Shield: Conditionally map ffmpeg path parameters to avoid NullPointerException
        String ffmpegDir = YtDlpManager.getFfmpegDir();
        if (ffmpegDir != null) {
            args.add("--ffmpeg-location");
            args.add(ffmpegDir);
        }

        args.add("--paths"); args.add("home:" + outDir.getAbsolutePath());
        args.add("--paths"); args.add("temp:" + tempDir.getAbsolutePath());
        args.add("--newline");
        args.add("--progress-template");
        args.add("[PROGRESS]%(progress._percent_str)s|%(progress.speed)s|%(progress.eta)s|%(progress._total_bytes_estimate)s");
        args.add("-f");
        args.add(item.isPlaylist() ? item.getFormatId() : item.getFormatId() + "+bestaudio[ext=m4a]/best");
        args.add("--merge-output-format"); 
        args.add("mp4");
        args.add("-o");
        args.add(item.isPlaylist() ? "%(playlist_index)s - %(title)s.%(ext)s" : outFile.getName());
        args.add(item.getUrl());

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        process = pb.start();

        boolean streamContainsError = false; 
        StringBuilder errorLog = new StringBuilder();

        if (isCancelled()) {
            killProcessTree();
            return null;
        }

        // Enforce UTF-8 to prevent string fragmentation on multi-language metadata streams
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase().contains("error:") || line.toLowerCase().contains("fatal:")) {
                    streamContainsError = true;
                    errorLog.append(line).append("\n");
                }
                parseLine(line);
            }
        } catch (Exception e) {
            if (isCancelled()) {
                killProcessTree();
                return null;
            }
        }

        if (isCancelled()) {
            killProcessTree();
            return null;
        }

        int exit = process.waitFor();

        if (item.isInterrupted() || isCancelled()) {
            return null;
        }

        if (exit != 0 || streamContainsError) {
            Platform.runLater(() -> {
                if (!item.isInterrupted() && !isCancelled()) {
                    item.setSpeed("0.0 B/s");
                    item.setEta("--:--:--");
                    item.setStatus(VideoStatus.FAILED);
                }
            });
            throw new RuntimeException("CLI Process terminated with error code " + exit + ". Log: " + errorLog.toString());
        }

        Platform.runLater(() -> {
            item.setProgress(1.0);
            item.setSpeed("Done");
            item.setEta("00:00:00");
            item.setStatus(VideoStatus.COMPLETED);
            String details = "File: " + item.getTitle() + "\nQuality: " + item.getQuality() + "\nSize: " + formatBytes(item.getTotalSize());
            Toast.show(item.getTitle(), details);
        });

        return null;
    }

    private long fetchSizeSequentially() {
        Process metadataProcess = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                YtDlpManager.getBinaryPath(),
                "--no-playlist",
                "--no-warnings",
                "--simulate",
                "--print", "%(filesize,filesize_approx)s",
                "-f", item.isPlaylist() ? item.getFormatId() : item.getFormatId() + "+bestaudio[ext=m4a]/best",
                item.getUrl()
            );
            builder.redirectErrorStream(true);
            metadataProcess = builder.start();

            long calculatedSize = 0L;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(metadataProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isCancelled()) {
                        metadataProcess.destroyForcibly();
                        return 0L;
                    }
                    for (String part : line.split(",")) {
                        part = part.trim();
                        if (!part.isEmpty() && !part.equalsIgnoreCase("none") && !part.equalsIgnoreCase("NA")) {
                            try {
                                calculatedSize = (long) Double.parseDouble(part);
                                if (calculatedSize > 0) break;
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
            metadataProcess.waitFor();
            return calculatedSize;
        } catch (Exception e) {
            if (metadataProcess != null) metadataProcess.destroyForcibly();
            return 0L;
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        killProcessTree();
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected void cancelled() {
        super.cancelled();
        killProcessTree();
    }

    private synchronized void killProcessTree() {
        try {
            if (process != null && process.isAlive()) {
                System.out.println("Force terminating native execution pipes...");
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.getInputStream().close();
            }
        } catch (Exception ignored) {}
    }

    private void parseLine(String line) {
        if (line.contains("[Merger]") || line.contains("Merging formats into")) {
            if (!isMerging) {
                isMerging = true;
                Platform.runLater(() -> {
                    if (item.isInterrupted()) return; 
                    item.setStatus(VideoStatus.MERGING);
                    item.setSpeed("Merging…");
                    item.setProgress(-1);
                });
            }
            return;
        }

        Matcher m = PROGRESS_RE.matcher(line);
        if (!m.find()) return;

        try {
            double pct  = Double.parseDouble(m.group(1).trim()) / 100.0;
            String spdR = m.group(2).trim();
            String etaR = m.group(3).trim();
            String szR  = m.group(4).trim();

            String spd = spdR.equalsIgnoreCase("NA") ? "0.0 B/s"  : formatSpeed(parseNumericString(spdR));
            String eta = etaR.equalsIgnoreCase("NA") ? "--:--:--" : formatTime(parseNumericString(etaR));

            if (!szR.equalsIgnoreCase("NA") && !szR.isEmpty()) {
                long sz = parseNumericString(szR);
                if (sz > 0) this.totalSize = sz;
            }

            final long   ts = this.totalSize;
            final String s  = spd, e = eta;
            final double p  = pct;

            long now        = System.currentTimeMillis();
            long lastUpdate = lastUiUpdateTime.get();

            if (p >= 1.0 || (now - lastUpdate) > UI_UPDATE_THROTTLE_MS) {
                if (lastUiUpdateTime.compareAndSet(lastUpdate, now) || p >= 1.0) {
                    Platform.runLater(() -> {
                        if (item.isInterrupted() || isCancelled()) return;

                        if (!isMerging && item.getStatus() != VideoStatus.DOWNLOADING) {
                            item.setStatus(VideoStatus.DOWNLOADING);
                        }

                        item.setProgress(p);
                        item.setSpeed(s);
                        item.setEta(e);

                        if (ts > 0) {
                            item.setTotalSize(ts);
                            item.setSize(formatBytes(ts));
                        }
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    private void setStatus(VideoStatus s) {
        Platform.runLater(() -> {
            if (!item.isInterrupted() && !isCancelled()) {
                item.setStatus(s);
            }
        });
    }

    private static long parseNumericString(String raw) {
        try {
            return (long) Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String formatSpeed(long bps) {
        if (bps <= 0) return "0.0 B/s";
        String[] u = {"B/s", "KB/s", "MB/s", "GB/s"};
        int i = Math.min((int)(Math.log(bps) / Math.log(1024)), u.length - 1);
        return DF.format(bps / Math.pow(1024, i)) + " " + u[i];
    }

    private static String formatTime(long s) {
        if (s <= 0 || s > 864000) return "--:--:--";
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private static String formatBytes(long b) {
        if (b <= 0) return "Unknown";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = Math.min((int)(Math.log(b) / Math.log(1024)), u.length - 1);
        return DF.format(b / Math.pow(1024, i)) + " " + u[i];
    }
}