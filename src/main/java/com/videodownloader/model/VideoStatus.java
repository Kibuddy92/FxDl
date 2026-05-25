package com.videodownloader.model;

public enum VideoStatus {
    QUEUED,
    CONNECTING,
    FETCHING,
    DOWNLOADING,
    MERGING,
    STOPPED,
    COMPLETED,
    CANCELLED,
    FAILED,
    ERROR
}
