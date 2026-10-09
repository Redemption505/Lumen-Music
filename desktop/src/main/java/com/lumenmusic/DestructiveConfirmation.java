package com.lumenmusic;

import java.util.function.Consumer;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

/** A consistent last checkpoint before discarding personal data. */
public final class DestructiveConfirmation {
    private DestructiveConfirmation() { }

    public static boolean confirm(Window owner, String title, String detail, Consumer<DialogPane> theme) {
        ButtonType proceed = new ButtonType("确认", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, detail, cancel, proceed);
        if (owner != null) alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(title);
        DialogPane pane = alert.getDialogPane();
        pane.setId("destructive-confirmation");
        pane.setMinWidth(360);
        pane.setMaxWidth(520);
        if (theme != null) theme.accept(pane);
        else if (owner != null && owner.getScene() != null) {
            pane.getStylesheets().setAll(owner.getScene().getStylesheets());
            pane.getStyleClass().addAll(owner.getScene().getRoot().getStyleClass().stream()
                    .filter(style -> style.endsWith("-theme")).toList());
            pane.setStyle(owner.getScene().getRoot().getStyle());
        }
        Button confirmButton = (Button) pane.lookupButton(proceed);
        confirmButton.setId("destructive-confirm");
        confirmButton.setDefaultButton(false);
        confirmButton.getStyleClass().add("danger-button");
        Button cancelButton = (Button) pane.lookupButton(cancel);
        cancelButton.setId("destructive-cancel");
        cancelButton.setDefaultButton(true);
        alert.setOnShown(event -> cancelButton.requestFocus());
        FriendlyTooltips.install(pane);
        return alert.showAndWait().orElse(cancel) == proceed;
    }
}
