package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ObjectProperty;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/** One full-ratio local cover, or a consistent collage of actual track artwork. */
final class PlaylistArtwork extends StackPane {
    private final double size;
    private final Function<URI, ObjectProperty<Image>> images;
    private final List<ImageView> views = new ArrayList<>();
    private final Tooltip tooltip = new Tooltip();

    PlaylistArtwork(double size, Function<URI, ObjectProperty<Image>> images) {
        this.size = size;
        this.images = images;
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        getStyleClass().add("playlist-artwork");
        Rectangle clip = new Rectangle(size, size);
        clip.setArcWidth(18); clip.setArcHeight(18); setClip(clip);
        Tooltip.install(this, FriendlyTooltips.configure(tooltip));
        update(null);
    }

    void update(LibraryClient.Playlist playlist) {
        views.forEach(view -> view.imageProperty().unbind());
        views.clear(); getChildren().clear();
        if (playlist != null && !playlist.coverDataUri().isBlank()) {
            try {
                String value = playlist.coverDataUri();
                Image image = new Image(new ByteArrayInputStream(Base64.getDecoder().decode(value.substring(value.indexOf(',') + 1))));
                if (!image.isError()) {
                    ImageView view = imageView(size); view.setId("playlist-custom-cover-image"); view.setImage(image);
                    getChildren().add(view); tooltip.setText("自选封面 · 完整展示"); return;
                }
            } catch (IllegalArgumentException ignored) { }
            tooltip.setText("自选封面暂不可用，原始内容仍保留");
        } else tooltip.setText("歌单拼图 · 来自已有曲目封面");
        List<URI> covers = playlist == null ? List.of() : playlist.tracks().stream().map(Track::coverUri)
                .filter(uri -> uri != null).distinct().limit(4).toList();
        if (covers.size() == 1) { getChildren().add(tile(covers.get(0), size, 0)); return; }
        GridPane collage = new GridPane(); collage.setHgap(2); collage.setVgap(2);
        for (int index = 0; index < 4; index++)
            collage.add(tile(index < covers.size() ? covers.get(index) : null, (size - 2) / 2, index), index % 2, index / 2);
        getChildren().add(collage);
        if (covers.isEmpty()) tooltip.setText("歌单封面 · 添加曲目后自动组合，也可选择本地图片");
    }

    private StackPane tile(URI cover, double width, int index) {
        Label placeholder = new Label("♪"); placeholder.getStyleClass().add("artwork-placeholder");
        ImageView image = imageView(width); image.setId("playlist-collage-image-" + index);
        if (cover != null) image.imageProperty().bind(images.apply(cover));
        placeholder.visibleProperty().bind(image.imageProperty().isNull());
        StackPane tile = new StackPane(placeholder, image);
        tile.setMinSize(width, width); tile.setMaxSize(width, width);
        tile.getStyleClass().add("playlist-artwork-tile"); return tile;
    }

    private ImageView imageView(double width) {
        ImageView view = new ImageView(); view.setPreserveRatio(true); view.setSmooth(true);
        view.setFitWidth(width); view.setFitHeight(width); views.add(view); return view;
    }
}
