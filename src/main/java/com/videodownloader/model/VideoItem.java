package com.videodownloader.model;

import javafx.beans.property.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a single video download — the model backing the TableView.
 */
public class VideoItem {

    private final StringProperty  title     = new SimpleStringProperty("Fetching…");
    private final StringProperty  url       = new SimpleStringProperty();
    private final StringProperty  quality   = new SimpleStringProperty("-");
    private final StringProperty  size      = new SimpleStringProperty("Unknown");
    private final LongProperty    totalSize = new SimpleLongProperty(0);
    private final DoubleProperty  progress  = new SimpleDoubleProperty(0.0);
    private final StringProperty  speed     = new SimpleStringProperty("0 B/s");
    private final StringProperty  eta       = new SimpleStringProperty("--:--:--");
    private final ObjectProperty<VideoStatus> status =
            new SimpleObjectProperty<>(VideoStatus.QUEUED);
    private final StringProperty  filePath  = new SimpleStringProperty();
    private final StringProperty  formatId  = new SimpleStringProperty();

    // Add this field alongside your existing variables
    private final AtomicBoolean userCancelled = new AtomicBoolean(false);

    public VideoItem() {}

    public VideoItem(String url, String title, String formatId,
                     String quality, long totalSize, String filePath) {
        this.url.set(url);
        this.title.set(title);
        this.formatId.set(formatId);
        this.quality.set(quality);
        this.totalSize.set(totalSize);
        this.filePath.set(filePath);
        this.size.set(totalSize > 0 ? formatBytes(totalSize) : "Unknown");
    }

    

    public void setInterrupted(boolean value) {
        this.userCancelled.set(value);
    }

    public boolean isInterrupted() {
        return this.userCancelled.get();
    }

    public String  getTitle()                              { return title.get(); }
    public StringProperty titleProperty()                  { return title; }
    public void    setTitle(String v)                      { title.set(v); }

    public String  getUrl()                                { return url.get(); }
    public StringProperty urlProperty()                    { return url; }
    public void    setUrl(String v)                        { url.set(v); }

    public String  getQuality()                            { return quality.get(); }
    public StringProperty qualityProperty()                { return quality; }
    public void    setQuality(String v)                    { quality.set(v); }

    public String  getSize()                               { return size.get(); }
    public StringProperty sizeProperty()                   { return size; }
    public void    setSize(String v)                       { size.set(v); }

    public long    getTotalSize()                          { return totalSize.get(); }
    public LongProperty totalSizeProperty()                { return totalSize; }
    public void    setTotalSize(long v)                    { totalSize.set(v); }

    public double  getProgress()                           { return progress.get(); }
    public DoubleProperty progressProperty()               { return progress; }
    public void    setProgress(double v)                   { progress.set(v); }

    public String  getSpeed()                              { return speed.get(); }
    public StringProperty speedProperty()                  { return speed; }
    public void    setSpeed(String v)                      { speed.set(v); }

    public String  getEta()                                { return eta.get(); }
    public StringProperty etaProperty()                    { return eta; }
    public void    setEta(String v)                        { eta.set(v); }

    public VideoStatus getStatus()                         { return status.get(); }
    public ObjectProperty<VideoStatus> statusProperty()    { return status; }
    public void    setStatus(VideoStatus v)                { status.set(v); }

    public String  getFilePath()                           { return filePath.get(); }
    public StringProperty filePathProperty()               { return filePath; }
    public void    setFilePath(String v)                   { filePath.set(v); }

    public String  getFormatId()                           { return formatId.get(); }
    public StringProperty formatIdProperty()               { return formatId; }
    public void    setFormatId(String v)                   { formatId.set(v); }

    // Transient — not persisted, set before task launch
    private transient boolean playlist = false;
    public boolean isPlaylist()            { return playlist; }
    public void    setPlaylist(boolean v)  { playlist = v; }

    private static String formatBytes(long b) {
        if (b <= 0) return "Unknown";
        String[] u = {"B","KB","MB","GB","TB"};
        int i = (int)(Math.log(b) / Math.log(1024));
        i = Math.min(i, u.length - 1);
        return String.format("%.1f %s", b / Math.pow(1024, i), u[i]);
    }
}
