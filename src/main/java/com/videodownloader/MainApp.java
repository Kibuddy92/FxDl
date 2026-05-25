package com.videodownloader;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URL;

import com.videodownloader.controller.VideoMainController;

public class MainApp extends Application {

    private Stage primaryStage;
    private VideoMainController controller;
    private TrayIcon trayIcon;
    private boolean trayAvailable = false;

    @Override
    public void start(Stage stage) throws IOException {
        this.primaryStage = stage;

        // Linux GTK rendering hints — must be set before any AWT/Swing calls
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/com/videodownloader/fxml/video-main.fxml")
            );
            Parent root = loader.load();

            Scene scene = new Scene(root);
            this.controller = loader.getController();

            applyTheme(scene, VideoSettings.isDarkMode());

            URL appIconURL = getClass().getResource("/com/videodownloader/icons/vdl-icon-32.png");
            if (appIconURL != null) {
                stage.getIcons().add(new Image(appIconURL.openStream()));
            }

            stage.setTitle("FxDl — Video Download Manager");
            stage.setScene(scene);

            // Try to set up system tray — Linux support is inconsistent
            trayAvailable = setupSystemTray();

            stage.setOnCloseRequest(event -> {
                if (trayAvailable) {
                    // Minimise to tray if tray is available
                    event.consume();
                    stage.hide();
                } else {
                    // No tray — just exit cleanly
                    event.consume();
                    if (controller != null) controller.shutdown();
                    Platform.exit();
                    System.exit(0);
                }
            });

            // Only suppress implicit exit if tray is available
            Platform.setImplicitExit(!trayAvailable);

            stage.show();

        } catch (Exception e) {
            System.err.println("Failed to start application:");
            e.printStackTrace();
        }
    }

    /** Returns true if tray was set up successfully. */
    private boolean setupSystemTray() {
        if (!SystemTray.isSupported()) {
            System.out.println("System tray not supported on this platform.");
            return false;
        }

        try {
            // Must run on Swing EDT
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    SystemTray tray = SystemTray.getSystemTray();

                    URL imageURL = getClass().getResource("/com/videodownloader/icons/vdl-icon-32.png");
                    java.awt.Image iconImage = (imageURL != null)
                        ? Toolkit.getDefaultToolkit().getImage(imageURL)
                        : Toolkit.getDefaultToolkit().createImage(new byte[0]);

                    trayIcon = new TrayIcon(iconImage, "FxDl", null);
                    trayIcon.setImageAutoSize(true);

                    // Double-click / single-click (Linux uses single-click)
                    trayIcon.addActionListener(e -> Platform.runLater(() -> {
                        primaryStage.show();
                        primaryStage.toFront();
                    }));

                    trayIcon.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseReleased(MouseEvent e) {
                            if (e.getButton() == MouseEvent.BUTTON3) {
                                Platform.runLater(() -> showJavaFXTrayMenu(e.getXOnScreen(), e.getYOnScreen()));
                            }
                            // Linux: single left click to restore
                            if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                                Platform.runLater(() -> {
                                    primaryStage.show();
                                    primaryStage.toFront();
                                });
                            }
                        }
                    });

                    tray.add(trayIcon);

                } catch (AWTException e) {
                    System.err.println("Could not add tray icon: " + e.getMessage());
                }
            });
            return true;
        } catch (Exception e) {
            System.err.println("System tray setup failed: " + e.getMessage());
            return false;
        }
    }

    private void showJavaFXTrayMenu(double screenX, double screenY) {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem restoreItem = new MenuItem("Open Manager");
        restoreItem.setOnAction(e -> {
            primaryStage.show();
            primaryStage.toFront();
        });

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> {
            if (trayIcon != null && SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
            if (controller != null) controller.shutdown();
            Platform.exit();
            System.exit(0);
        });

        contextMenu.getItems().addAll(restoreItem, new SeparatorMenuItem(), exitItem);
        contextMenu.setAutoHide(true);

        // On Linux the stage may be hidden — show it briefly off-screen so the
        // context menu has an owner window to attach to, then hide again
        if (!primaryStage.isShowing()) {
            primaryStage.setOpacity(0);
            primaryStage.show();
            contextMenu.show(primaryStage, screenX, screenY - 15);
            contextMenu.setOnHidden(ev -> {
                primaryStage.hide();
                primaryStage.setOpacity(1);
            });
        } else {
            contextMenu.show(primaryStage, screenX, screenY - 15);
        }
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown(); // stops tasks then saves
        }
        if (trayIcon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }

    private void applyTheme(Scene scene, boolean darkMode) {
        String dark = getClass().getResource("/com/videodownloader/css/video-dark.css").toExternalForm();
        String light = getClass().getResource("/com/videodownloader/css/video-light.css").toExternalForm();
        scene.getStylesheets().add(darkMode ? dark : light);
    }

    public static void main(String[] args) {
        launch(args);
    }
}