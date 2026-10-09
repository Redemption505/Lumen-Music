package com.lumenmusic;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.input.ScrollEvent;

/** Installs wheel smoothing on logical UI content, including content added later. */
final class SmoothScrolling {
    private SmoothScrolling() { }

    static void install(Node node) {
        if (node == null || node.getProperties().putIfAbsent(SmoothScrolling.class, Boolean.TRUE) != null) return;
        if (node instanceof DialogPane dialog) {
            install(dialog.getContent());
            dialog.contentProperty().addListener((ignored, before, after) -> install(after));
        } else if (node instanceof ScrollPane pane) {
            SmoothScrollPane.install(pane);
            install(pane.getContent());
            pane.contentProperty().addListener((ignored, before, after) -> install(after));
        } else if (node instanceof ListView<?> list) {
            SmoothListScroll.install(list);
        } else if (node instanceof TreeView<?> tree) {
            SmoothListScroll.install(tree);
        } else if (node instanceof TableView<?> table) {
            SmoothListScroll.install(table);
        } else if (node instanceof TextArea area) {
            Runnable installSkin = () -> {
                Node scroller = area.lookup(".scroll-pane");
                if (scroller instanceof ScrollPane pane) SmoothScrollPane.install(pane);
            };
            area.skinProperty().addListener((ignored, before, after) -> installSkin.run());
            installSkin.run();
        } else if (node instanceof ComboBox<?> combo) {
            Runnable installPopup = () -> {
                if (combo.getSkin() instanceof ComboBoxListViewSkin<?> skin) install(skin.getPopupContent());
            };
            combo.skinProperty().addListener((ignored, before, after) -> installPopup.run());
            combo.showingProperty().addListener((ignored, before, after) -> { if (after) installPopup.run(); });
            installPopup.run();
        } else if (node instanceof TabPane tabs) {
            tabs.getTabs().forEach(SmoothScrolling::installTab);
            tabs.getTabs().addListener((ListChangeListener<Tab>) change -> {
                while (change.next()) change.getAddedSubList().forEach(SmoothScrolling::installTab);
            });
        } else if (node instanceof SplitPane split) {
            split.getItems().forEach(SmoothScrolling::install);
            watch(split.getItems());
        } else if (node instanceof TitledPane titled) {
            install(titled.getContent());
            titled.contentProperty().addListener((ignored, before, after) -> install(after));
        } else if (node instanceof Parent parent && !(node instanceof Control)) {
            parent.getChildrenUnmodifiable().forEach(SmoothScrolling::install);
            watch(parent.getChildrenUnmodifiable());
        }
    }

    private static void installTab(Tab tab) {
        if (tab.getProperties().putIfAbsent(SmoothScrolling.class, Boolean.TRUE) != null) return;
        install(tab.getContent());
        tab.contentProperty().addListener((ignored, before, after) -> install(after));
    }

    private static void watch(javafx.collections.ObservableList<Node> children) {
        children.addListener((ListChangeListener<Node>) change -> {
            while (change.next()) change.getAddedSubList().forEach(SmoothScrolling::install);
        });
    }

    /** Only discrete wheel steps are animated; gestures already provide continuous motion. */
    static boolean discreteWheel(ScrollEvent event) {
        return !event.isDirect() && !event.isInertia() && event.getTouchCount() == 0
                && !event.isShiftDown() && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()
                && event.getTextDeltaYUnits() == ScrollEvent.VerticalTextScrollUnits.LINES
                && Math.abs(event.getTextDeltaY()) >= 0.9
                && Math.abs(event.getDeltaX()) <= Math.abs(event.getDeltaY()) * 0.25;
    }

    /** A parent scroller must not capture wheel events meant for an inner scroller. */
    static boolean ownsScroll(Node owner, Object target) {
        if (!(target instanceof Node node)) return false;
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof ScrollBar) return false;
            if (current == owner) return true;
            if (current instanceof ScrollPane || current instanceof ListView<?> || current instanceof TreeView<?> || current instanceof TableView<?>
                    || current instanceof TextArea || current instanceof ComboBox<?>) return false;
        }
        return false;
    }
}
