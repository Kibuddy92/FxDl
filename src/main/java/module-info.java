module com.videodownloader {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.gson;
    requires java.net.http;
    requires java.desktop;
    requires com.google.zxing;

    opens com.videodownloader             to javafx.fxml;
    opens com.videodownloader.controller  to javafx.fxml;
    opens com.videodownloader.model       to javafx.fxml, com.google.gson;
    opens com.videodownloader.service     to javafx.fxml;
    opens com.videodownloader.util        to com.google.gson;

    exports com.videodownloader;
    exports com.videodownloader.controller;
    exports com.videodownloader.model;
    exports com.videodownloader.service;
    exports com.videodownloader.util;
}
