package com.lumenmusic;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.Tooltip;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

/** Shared timing, wrapping and popup theming, including dynamically created cells. */
public final class FriendlyTooltips {
    private static final Object INSTALLED = new Object();
    private static final Object CONFIGURED = new Object();

    private FriendlyTooltips() { }

    public static Tooltip configure(Tooltip tooltip) {
        if (tooltip == null || tooltip.getProperties().putIfAbsent(CONFIGURED, Boolean.TRUE) != null) return tooltip;
        tooltip.setShowDelay(Duration.millis(500));
        tooltip.setHideDelay(Duration.millis(180));
        // Keep the tooltip readable during a sustained hover.
        tooltip.setShowDuration(Duration.hours(24));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(380);
        tooltip.getStyleClass().add("friendly-tooltip");
        tooltip.addEventHandler(WindowEvent.WINDOW_SHOWING, event -> refreshTheme(tooltip));
        return tooltip;
    }

    public static void install(Node node) {
        if (node == null || node.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) != null) return;
        if (node instanceof Control control) {
            configure(control.getTooltip());
            control.tooltipProperty().addListener((ignored, before, after) -> configure(after));
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(FriendlyTooltips::install);
            parent.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
                while (change.next()) if (change.wasAdded()) change.getAddedSubList().forEach(FriendlyTooltips::install);
            });
        }
    }

    public static void refreshTheme(Tooltip tooltip) {
        tooltip.getScene().setFill(javafx.scene.paint.Color.TRANSPARENT);
        Parent root = tooltip.getScene().getRoot();
        Window owner = tooltip.getOwnerWindow();
        String palette = "";
        if (owner != null && owner.getScene() != null) {
            tooltip.getScene().getStylesheets().setAll(owner.getScene().getStylesheets());
            root.getStyleClass().removeIf(style -> style.endsWith("-theme"));
            root.getStyleClass().addAll(owner.getScene().getRoot().getStyleClass().stream()
                    .filter(style -> style.endsWith("-theme")).toList());
            palette = owner.getScene().getRoot().getStyle();
        }
        // The PopupControl bridge is a second root behind the actual tooltip label.
        // A themed .root background there otherwise paints a rectangular second bubble.
        root.setStyle((palette.isBlank() ? "" : palette + "; ")
                + "-fx-background-color: transparent; -fx-background-image: null; -fx-border-color: transparent; -fx-effect: null; -fx-padding: 0;");
        root.setMouseTransparent(true);
    }
}
