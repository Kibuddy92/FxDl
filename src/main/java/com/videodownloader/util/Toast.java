package com.videodownloader.util;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class Toast {

    public static void show(String fileName, String message) {
        // Guarantee execution safe on JavaFX UI thread
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> show(fileName, message));
            return;
        }

        Stage toastStage = new Stage();
        toastStage.initStyle(StageStyle.TRANSPARENT);
        toastStage.setAlwaysOnTop(true);

        // --- ICON LAYOUT (Font Awesome Video Icon) ---
        Label iconLabel = new Label("\uf1c8"); // Film strip icon code
        iconLabel.setStyle("-fx-font-family: 'Font Awesome 7 Free Solid'; -fx-font-size: 26px; -fx-text-fill: #faf283;");

        // --- TEXT LAYOUT ---
        VBox textLayout = new VBox(2);
        Label titleLabel = new Label("Download Complete");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");
        
        Label msgLabel = new Label(message);
        msgLabel.setStyle("-fx-text-fill: #a0a0aa; -fx-font-size: 12px;");
        msgLabel.setWrapText(true);
        msgLabel.setMaxWidth(280);
        textLayout.getChildren().addAll(titleLabel, msgLabel);

        // --- PARENT WRAPPER (FxDl Dark Panel Theme) ---
        HBox root = new HBox(15);
        root.setPadding(new Insets(15));
        root.setAlignment(Pos.CENTER_LEFT);
        root.setStyle(
            "-fx-background-color: rgba(30, 30, 30, 0.98); " +
            "-fx-background-radius: 8px; " +
            "-fx-border-color: #3e3e42; " +
            "-fx-border-width: 1px; " +
            "-fx-border-radius: 8px; " +
            "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.6), 10, 0, 0, 4);"
        );
        root.getChildren().addAll(iconLabel, textLayout);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        toastStage.setScene(scene);

        // --- CORNER MONITOR POSITIONING ---
        Screen screen = Screen.getPrimary();
        double margin = 25.0;
        
        // Temporarily force an invisible show to calculate bounds accurately
        toastStage.setOpacity(0);
        toastStage.show();
        
        double x = screen.getVisualBounds().getMaxX() - root.getWidth() - margin;
        double y = screen.getVisualBounds().getMaxY() - root.getHeight() - margin;
        
        toastStage.setX(x);
        toastStage.setY(y);

        // --- FLY-IN AND FADE ANIMS ---
        root.setTranslateY(20); // Offsets the start position down 20px
        
        FadeTransition fadeIn = new FadeTransition(Duration.millis(400), root);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);

        TranslateTransition slideIn = new TranslateTransition(Duration.millis(400), root);
        slideIn.setByY(-20); // Pulls it straight back up to baseline

        ParallelTransition entryAnimation = new ParallelTransition(fadeIn, slideIn);
        PauseTransition stayOnScreen = new PauseTransition(Duration.seconds(6.0));
        
        FadeTransition fadeOut = new FadeTransition(Duration.millis(500), root);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);

        // Chain the sequence sequentially
        SequentialTransition animationChain = new SequentialTransition(entryAnimation, stayOnScreen, fadeOut);
        animationChain.setOnFinished(e -> toastStage.close());
        
        toastStage.setOpacity(1.0); // Reset window transparency baseline 
        animationChain.play();
    }
}