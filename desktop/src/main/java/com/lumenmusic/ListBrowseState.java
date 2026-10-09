package com.lumenmusic;

import java.util.List;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;

/** Restores browsing after a data refresh without changing the playback queue. */
final class ListBrowseState {
    private final List<String> selected;
    private final double position;
    final double pagePosition;

    private ListBrowseState(List<String> selected, double position, double pagePosition) {
        this.selected = List.copyOf(selected);
        this.position = position;
        this.pagePosition = pagePosition;
    }

    static ListBrowseState capture(ListView<Track> view, double pagePosition) {
        var flow = view.lookup(".virtual-flow");
        return new ListBrowseState(view.getSelectionModel().getSelectedItems().stream()
                .map(Track::id).toList(), flow instanceof VirtualFlow<?> virtual ? virtual.getPosition() : 0,
                pagePosition);
    }

    void restore(ListView<Track> view, BooleanSupplier stillCurrent) {
        view.getSelectionModel().clearSelection();
        for (int i = 0; i < view.getItems().size(); i++)
            if (selected.contains(view.getItems().get(i).id())) view.getSelectionModel().select(i);
        Platform.runLater(() -> {
            if (!stillCurrent.getAsBoolean()) return;
            view.applyCss();
            view.layout();
            if (view.lookup(".virtual-flow") instanceof VirtualFlow<?> flow) flow.setPosition(position);
        });
    }
}
