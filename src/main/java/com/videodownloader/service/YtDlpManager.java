package com.videodownloader.service;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

/**
 * Locates the yt-dlp and ffmpeg binaries (bundled in bin/ next to the JAR, or on PATH).
 * Optimized for cross-platform deployment (Windows Installers & Debian/Ubuntu Packages).
 */
public class YtDlpManager {

    private static String cachedPath = null;
    private static String cachedFfmpegDir = null;

    /**
     * Returns the absolute path to yt-dlp, or "yt-dlp" as a PATH fallback.
     */
    public static String getBinaryPath() {
        if (cachedPath != null) return cachedPath;

        String os      = System.getProperty("os.name").toLowerCase();
        String binName = os.contains("win") ? "yt-dlp.exe" : "yt-dlp";

        // 1. Look next to the JAR (bin/ sub-folder)
        try {
            String jarDir = new File(YtDlpManager.class
                .getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent();
            File candidate = new File(jarDir, "bin/" + binName);
            
            if (candidate.exists()) {
                // Read-only safety check for Linux root deployments (/usr/share)
                if (!os.contains("win") && !candidate.canExecute()) {
                    try {
                        candidate.setExecutable(true);
                    } catch (SecurityException ignored) {
                        // Thrown if running from a write-protected root installation folder
                    }
                }
                cachedPath = candidate.getAbsolutePath();
                return cachedPath;
            }
        } catch (Exception ignored) {}

        // 2. Fallback — rely on system PATH
        cachedPath = "yt-dlp";
        return cachedPath;
    }

    /**
     * Returns the directory containing ffmpeg (same bin/ folder as yt-dlp).
     * Returns null if no bundled directory is present, indicating system PATH fallback.
     */
    public static String getFfmpegDir() {
        if (cachedFfmpegDir != null) return cachedFfmpegDir;

        try {
            String jarDir = new File(YtDlpManager.class
                .getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent();
            File binDir = new File(jarDir, "bin");
            
            if (binDir.exists()) {
                cachedFfmpegDir = binDir.getAbsolutePath();
                return cachedFfmpegDir;
            }
        } catch (Exception ignored) {}
        
        return null; // Signals downstream logic to use global 'ffmpeg' execution mapping
    }

    /**
     * Returns the installed yt-dlp version, or "not found".
     */
    public static String getVersion() {
        try {
            Process p = new ProcessBuilder(getBinaryPath(), "--version")
                .redirectErrorStream(true).start();
            String v = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return v.isBlank() ? "not found" : v;
        } catch (Exception e) {
            return "not found";
        }
    }

    /**
     * Returns true if yt-dlp is available (either bundled or on PATH).
     */
    public static boolean isAvailable() {
        return !getVersion().equals("not found");
    }

    /**
     * True if the URL is a streaming/social-media site that yt-dlp handles.
     */
    public static boolean isStreamingUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("youtube.com") || u.contains("youtu.be")
            || u.contains("vimeo.com")     || u.contains("dailymotion.com")
            || u.contains("twitch.tv")     || u.contains("twitter.com")
            || u.contains("x.com/")        || u.contains("tiktok.com")
            || u.contains("instagram.com") || u.contains("facebook.com")
            || u.contains("reddit.com")    || u.contains("bilibili.com")
            || u.contains("soundcloud.com")|| u.contains("bandcamp.com")
            || u.contains("nicovideo.jp")  || u.contains("rumble.com")
            || u.contains("odysee.com")    || u.contains("peertube")
            || u.contains("pornhub.com")   || u.contains("xvideos.com")
            || u.contains("xhamster.com")  || u.contains("xnxx.com");
    }
}