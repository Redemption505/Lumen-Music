package com.lumenmusic;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.net.URI;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;

/** A local draft; persistence never touches playback and failure keeps the draft open. */
final class PlaylistPresentationDialog implements AutoCloseable {
    private final Dialog<Void> dialog = new Dialog<>();
    private final TextArea description = new TextArea();
    private final Label feedback = new Label();
    private final Label count = new Label();
    private final LibraryClient.Playlist playlist;
    private final LibraryClient client;
    private final ExecutorService executor;
    private final Consumer<LibraryClient.Snapshot> changed;
    private final PlaylistArtwork artwork;
    private final VBox form;
    private final Button save;
    private final Button cancel;
    private String cover;
    private boolean busy;
    private boolean closed;

    PlaylistPresentationDialog(Window owner, LibraryClient.Playlist playlist, LibraryClient client,
            ExecutorService executor, Consumer<LibraryClient.Snapshot> changed,
            Function<URI, ObjectProperty<Image>> images, Consumer<DialogPane> theme) {
        this.playlist = playlist; this.client = client; this.executor = executor; this.changed = changed;
        cover = playlist.coverDataUri();
        dialog.initOwner(owner); dialog.initModality(Modality.NONE); dialog.setResizable(true);
        dialog.setTitle("歌单封面与说明");
        DialogPane pane = dialog.getDialogPane(); pane.setId("playlist-presentation-dialog"); theme.accept(pane);
        SmoothScrolling.install(pane);
        FriendlyTooltips.install(pane);
        ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        save = (Button) pane.lookupButton(saveType); save.setId("playlist-presentation-save"); save.getStyleClass().add("accent-button");
        cancel = (Button) pane.lookupButton(ButtonType.CANCEL); cancel.setId("playlist-presentation-cancel");
        artwork = new PlaylistArtwork(112, images); artwork.setId("playlist-cover-preview"); artwork.update(playlist);
        Button choose = new Button("选择本地封面"); choose.setId("playlist-cover-select"); choose.setOnAction(event -> selectCover());
        Button clear = new Button("使用曲目拼图"); clear.setId("playlist-cover-clear"); clear.setOnAction(event -> { cover = ""; refreshPreview(); feedback.setText("将使用已有曲目封面组合，保存后生效"); });
        FlowPane coverActions = new FlowPane(8, 8, choose, clear);
        Label name = new Label(playlist.name()); name.setWrapText(true); name.setMaxWidth(Double.MAX_VALUE); name.getStyleClass().add("section-title");
        Label coverHelp = new Label("PNG / JPEG，保留完整画面；封面随数据备份保存"); coverHelp.setWrapText(true); coverHelp.getStyleClass().add("muted");
        description.setId("playlist-description-input"); description.setWrapText(true); description.setPrefRowCount(4);
        description.setText(playlist.description()); description.setPromptText("写下这个歌单的场景或一段简短介绍");
        count.setId("playlist-description-count"); count.getStyleClass().add("muted");
        description.textProperty().addListener((ignored, before, after) -> updateCount());
        feedback.setId("playlist-presentation-feedback"); feedback.setWrapText(true); feedback.getStyleClass().add("muted");
        form = new VBox(10, name, artwork, coverActions, coverHelp, new Label("简短说明"), description, count);
        ScrollPane scroll = new ScrollPane(form); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setId("playlist-presentation-scroll"); scroll.setPrefViewportHeight(Math.max(260, Math.min(450, owner.getHeight() - 180)));
        VBox content = new VBox(12, scroll, feedback); content.setPrefWidth(460); VBox.setVgrow(scroll, Priority.ALWAYS); pane.setContent(content);
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> { event.consume(); if (!busy) persist(); });
        dialog.setOnCloseRequest(event -> { if (busy) event.consume(); });
        dialog.setOnHidden(event -> closed = true);
        updateCount();
    }

    void show() { dialog.show(); }
    boolean isShowing() { return dialog.isShowing(); }

    private void updateCount() {
        int length = description.getText().codePointCount(0, description.getText().length());
        count.setText(length + " / 280 字");
        save.setDisable(busy || length > 280);
    }

    private void selectCover() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("选择歌单封面");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG / JPEG 图片", "*.png", "*.jpg", "*.jpeg"));
        var file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
        if (file != null) chooseCover(file.toPath());
    }

    void chooseCover(Path file) {
        setBusy(true); feedback.setText("正在读取封面...");
        executor.submit(() -> {
            try {
                String value = PlaylistCoverData.fromFile(file);
                Platform.runLater(() -> { if (closed) return; cover = value; refreshPreview(); setBusy(false); feedback.setText("封面已选好，保存后生效"); });
            } catch (Exception error) {
                Platform.runLater(() -> { if (closed) return; setBusy(false); feedback.setText("读取失败：" + error.getMessage()); });
            }
        });
    }

    private void refreshPreview() { artwork.update(new LibraryClient.Playlist(playlist.id(), playlist.name(), playlist.tracks(), description.getText(), cover)); }

    private void persist() {
        String draft = description.getText(); String image = cover;
        setBusy(true); feedback.setText("正在保存...");
        executor.submit(() -> {
            try {
                var snapshot = client.updatePlaylistMetadata(playlist.id(), draft, image);
                Platform.runLater(() -> { if (closed) return; changed.accept(snapshot); setBusy(false); dialog.close(); });
            } catch (Exception error) {
                Platform.runLater(() -> { if (closed) return; setBusy(false); feedback.setText("保存失败，调整已保留：" + error.getMessage()); });
            }
        });
    }

    private void setBusy(boolean value) { busy = value; form.setDisable(value); cancel.setDisable(value); updateCount(); }
    @Override public void close() { closed = true; setBusy(false); dialog.close(); }
}
