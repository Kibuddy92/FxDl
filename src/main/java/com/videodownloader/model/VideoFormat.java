package com.videodownloader.model;

import java.text.DecimalFormat;

/**
 * Represents a specific quality/stream option returned by yt-dlp.
 */
public class VideoFormat {

    private final String  id;
    private final String  ext;
    private final String  res;
    private final String  note;
    private final Integer height;
    private final long    filesize;
    private final double  tbr;
    private final double  duration;

    private static final DecimalFormat df = new DecimalFormat("#,##0.0");

    public VideoFormat(String id, String ext, String res, String note,
                       Integer height, long filesize, double tbr, double duration) {
        this.id       = id;
        this.ext      = ext;
        this.res      = res;
        this.note     = note;
        this.height   = height;
        this.filesize = filesize;
        this.tbr      = tbr;
        this.duration = duration;
    }

    public String  getId()       { return id; }
    public String  getExt()      { return ext; }
    public String  getRes()      { return res; }
    public String  getNote()     { return note; }
    public Integer getHeight()   { return height; }
    public long    getFilesize() { return filesize; }
    public double  getTbr()      { return tbr; }
    public double  getDuration() { return duration; }

    public String getQualityLabel() {
        if (height == null || height == 0)
            return (note != null && !note.isEmpty()) ? note : "Unknown";
        return switch (height) {
            case 144  -> "144p";
            case 240  -> "240p";
            case 360  -> "360p";
            case 480  -> "480p";
            case 720  -> "720p (HD)";
            case 1080 -> "1080p (Full HD)";
            case 1440 -> "1440p (2K)";
            case 2160 -> "2160p (4K)";
            case 4320 -> "4320p (8K)";
            default   -> height + "p";
        };
    }

    @Override
    public String toString() {
        String label = getQualityLabel();
        String size  = filesize > 0 ? " — " + formatSize(filesize) : "";
        return String.format("%-18s [%s]%s", label, ext.toUpperCase(), size);
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "Unknown";
        String[] u = {"B","KB","MB","GB","TB"};
        int i = (int)(Math.log(bytes) / Math.log(1024));
        i = Math.min(i, u.length - 1);
        return df.format(bytes / Math.pow(1024, i)) + " " + u[i];
    }
}
