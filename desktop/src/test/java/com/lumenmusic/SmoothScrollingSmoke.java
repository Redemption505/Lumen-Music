package com.lumenmusic;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Actual JavaFX pulses and skins: nested content, new pages, text, trees and popup lists. */
public final class SmoothScrollingSmoke {
    private SmoothScrollingSmoke() { }
    public static void main(String[] args) { Application.launch(Harness.class, args); }

    public static final class Harness extends Application {
        private Stage stage;
        private ScrollPane outer;
        private ListView<Integer> list;
        private TextArea text;
        private TreeView<Integer> tree;
        private TableView<Integer> table;
        private ComboBox<Integer> combo;
        private VBox body;
        private ScrollPane dialogScroll;
        private ListView<Integer> dialogList;
        private TextArea dialogText;
        private VirtualFlow<?> flow;

        @Override public void start(Stage stage) {
            this.stage = stage;
            try {
                list = new ListView<>();
                for (int index = 0; index < 10_000; index++) list.getItems().add(index);
                list.setFixedCellSize(40);
                list.setPrefHeight(220);
                list.setMinHeight(220);
                text = new TextArea("Long note\n".repeat(150));
                text.setPrefHeight(160);
                text.setMinHeight(160);
                TreeItem<Integer> root = new TreeItem<>(-1);
                for (int index = 0; index < 500; index++) root.getChildren().add(new TreeItem<>(index));
                root.setExpanded(true);
                tree = new TreeView<>(root);
                tree.setPrefHeight(160);
                tree.setMinHeight(160);
                table = new TableView<>();
                TableColumn<Integer, Integer> column = new TableColumn<>("Segment");
                column.setCellValueFactory(value -> new javafx.beans.property.ReadOnlyObjectWrapper<>(value.getValue()));
                table.getColumns().add(column);
                for (int index = 0; index < 500; index++) table.getItems().add(index);
                table.setPrefHeight(160);
                table.setMinHeight(160);
                combo = new ComboBox<>();
                for (int index = 0; index < 120; index++) combo.getItems().add(index);
                body = new VBox(12, combo, list, text, tree, table);
                body.setMinHeight(1400);
                outer = new ScrollPane(body);
                outer.setFitToWidth(true);
                SmoothScrolling.install(outer);
                SmoothScrolling.install(outer);
                stage.setScene(new Scene(outer, 500, 650));
                stage.show();
                outer.applyCss();
                outer.layout();
                flow = (VirtualFlow<?>) list.lookup(".virtual-flow");
                check(flow != null, "virtualized 10,000 row list has real skin");
                check(text.lookup(".scroll-pane").getProperties().containsKey(SmoothScrollPane.class), "TextArea skin installed");
                check(tree.getProperties().containsKey(SmoothListScroll.class), "TreeView installed");
                check(table.getProperties().containsKey(SmoothListScroll.class), "batch table installed");
                ListView<Integer> added = new ListView<>();
                body.getChildren().add(added);
                check(added.getProperties().containsKey(SmoothListScroll.class), "dynamically added content installed");
                Tab tab = new Tab("later", new ListView<>());
                TabPane tabs = new TabPane(tab);
                body.getChildren().add(tabs);
                check(tab.getContent().getProperties().containsKey(SmoothListScroll.class), "inactive tab installed");
                ListView<Integer> replacement = new ListView<>();
                tab.setContent(replacement);
                check(replacement.getProperties().containsKey(SmoothListScroll.class), "replacement tab content installed");
                DialogPane dialog = new DialogPane();
                SmoothScrolling.install(dialog);
                dialogList = new ListView<>();
                for (int index = 0; index < 100; index++) dialogList.getItems().add(index);
                dialogList.setFixedCellSize(32);
                dialogList.setPrefHeight(140);
                dialogList.setMinHeight(140);
                dialogText = new TextArea("Dialog note\n".repeat(100));
                dialogText.setPrefHeight(140);
                dialogText.setMinHeight(140);
                VBox dialogBody = new VBox(8, dialogList, dialogText);
                dialogBody.setMinHeight(600);
                dialogScroll = new ScrollPane(dialogBody);
                dialogScroll.setFitToWidth(true);
                dialogScroll.setPrefHeight(320);
                dialog.setMinHeight(320);
                dialog.setPrefHeight(320);
                dialog.setContent(dialogScroll);
                body.getChildren().add(dialog);
                dialog.applyCss();
                outer.layout();
                check(dialogList.getProperties().containsKey(SmoothListScroll.class), "DialogPane content set after installation includes nested list");
                check(dialogText.lookup(".scroll-pane") instanceof ScrollPane
                        && dialogText.lookup(".scroll-pane").getProperties().containsKey(SmoothScrollPane.class),
                        "DialogPane content set after installation includes TextArea skin");
                DialogPane replacementDialog = new DialogPane();
                SmoothScrolling.install(replacementDialog);
                replacementDialog.setContent(new VBox());
                ListView<Integer> replacedDialogList = new ListView<>();
                replacementDialog.setContent(replacedDialogList);
                check(replacedDialogList.getProperties().containsKey(SmoothListScroll.class), "DialogPane replacement content installed");
                wheel(dialogList, -3, false, false);
                check(SmoothListScroll.install(dialogList).isRunning(), "DialogPane nested list owns wheel");
                check(!SmoothScrollPane.install(dialogScroll).isRunning() && !SmoothScrollPane.install(outer).isRunning(),
                        "DialogPane nested list does not animate either ancestor");
                ScrollPane dialogNoteScroll = (ScrollPane) dialogText.lookup(".scroll-pane");
                check(SmoothScrolling.ownsScroll(dialogNoteScroll, dialogNoteScroll.getContent()), "TextArea inner content resolves to its own ScrollPane");
                check(!SmoothScrolling.ownsScroll(dialogScroll, dialogNoteScroll.getContent()), "TextArea inner content excludes DialogPane ancestor scroller");
                wheel(dialogNoteScroll.getContent(), -3, false, false);
                check(SmoothScrollPane.install(dialogNoteScroll).isRunning(), "DialogPane note owns its wheel");
                check(!SmoothScrollPane.install(dialogScroll).isRunning() && !SmoothScrollPane.install(outer).isRunning(),
                        "DialogPane note does not animate either ancestor");
                wheel(body, -3, false, false);
                check(SmoothScrollPane.install(outer).isRunning(), "plain content starts parent wheel animation");
                wheel(list, -3, false, false);
                check(!SmoothScrollPane.install(outer).isRunning(), "nested list cancels parent animation");
                check(SmoothListScroll.install(list).isRunning(), "nested list owns its wheel animation");
                after(400, this::verifyNested);
            } catch (Throwable failure) { fail(failure); }
        }

        private void verifyNested() {
            try {
                check(flow.getPosition() > 0, "long list scrolls pixels on real pulses");
                check(outer.getVvalue() == 0, "outer pane does not also move for nested wheel");
                check(dialogText.getScrollTop() > 0 && dialogScroll.getVvalue() == 0,
                        "DialogPane TextArea wheel moves note while its ancestor stays still");
                ScrollPane noteScroll = (ScrollPane) text.lookup(".scroll-pane");
                wheel(noteScroll.getContent(), -3, false, false);
                check(SmoothScrollPane.install(noteScroll).isRunning(), "notes animate their own viewport");
                check(!SmoothScrollPane.install(outer).isRunning(), "note wheel does not start parent");
                after(350, () -> verifyText(noteScroll));
            } catch (Throwable failure) { fail(failure); }
        }

        private void verifyText(ScrollPane noteScroll) {
            try {
                check(text.getScrollTop() > 0, "TextArea wheel updates visible note offset");
                wheel(tree, -3, false, false);
                check(SmoothListScroll.install(tree).isRunning(), "tree wheel animates");
                wheel(table, -3, false, false);
                check(SmoothListScroll.install(table).isRunning(), "batch table wheel animates");
                combo.show();
                Node popup = ((ComboBoxListViewSkin<?>) combo.getSkin()).getPopupContent();
                check(popup instanceof ListView<?>, "combo has native virtualized popup list");
                ListView<?> choices = (ListView<?>) popup;
                choices.applyCss();
                choices.layout();
                wheel(choices, -3, false, false);
                check(SmoothListScroll.install(choices).isRunning(), "combo popup wheel animates");
                combo.hide();
                wheel(body, -3, false, false);
                check(SmoothScrollPane.install(outer).isRunning(), "parent wheel resumes");
                Event.fireEvent(outer, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false));
                check(!SmoothScrollPane.install(outer).isRunning(), "keyboard cancels pane animation");
                wheel(body, -3, false, true);
                check(!SmoothScrollPane.install(outer).isRunning(), "touchpad inertia stays native");
                wheel(body, -3, true, false);
                check(!SmoothScrollPane.install(outer).isRunning(), "direct touch stays native");
                wheel(body, -3, false, false);
                MotionPreferences.setReduced(true);
                check(!SmoothScrollPane.install(outer).isRunning(), "reduce motion cancels active parent animation");
                MotionPreferences.setReduced(false);
                SmoothListScroll.install(list).centerOn(300, true);
                check(SmoothListScroll.install(list).isRunning(), "lyric centering starts shared animation");
                after(90, this::verifyCenterMidpoint);
            } catch (Throwable failure) { fail(failure); }
        }

        private void verifyCenterMidpoint() {
            try {
                check(flow.getPosition() > 0.001 && flow.getPosition() < 0.0305, "lyric centering has intermediate movement");
                after(450, this::verifyCenterComplete);
            } catch (Throwable failure) { fail(failure); }
        }

        private void verifyCenterComplete() {
            try {
                check(!SmoothListScroll.install(list).isRunning(), "lyric centering settles");
                check(Math.abs(flow.getPosition() - 0.0298) < 0.001, "lyric row reaches viewport center");
                SmoothListScroll.install(list).centerOn(600, true);
                wheel(list, 3, false, false);
                MotionPreferences.setReduced(true);
                check(!SmoothListScroll.install(list).isRunning(), "wheel interrupts centering and reduce motion cancels wheel");
                SmoothListScroll.install(list).centerOn(900, true);
                check(!SmoothListScroll.install(list).isRunning() && flow.getPosition() > 0.089, "reduced motion centers immediately");
                MotionPreferences.setReduced(false);
                wheel(body, -3, false, false);
                stage.getScene().setRoot(new VBox());
                check(!SmoothScrollPane.install(outer).isRunning(), "detaching pane stops pulses");
                stage.close();
                System.out.println("SmoothScrollingSmoke PASS: nested panes, 10k list, TextArea, TreeView, batch table, dynamic DialogPane content, tabs, combo popup, native gestures, keyboard, reduced motion, lyric centering, detach");
                Platform.exit();
            } catch (Throwable failure) { fail(failure); }
        }

        private static void wheel(Node target, double lines, boolean direct, boolean inertia) {
            Event.fireEvent(target, new ScrollEvent(ScrollEvent.SCROLL, 100, 100, 100, 100,
                    false, false, false, false, direct, inertia, 0, lines * 40, 0, 0,
                    ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.LINES, lines, 0, null));
        }
        private void after(int millis, Runnable action) {
            PauseTransition pause = new PauseTransition(Duration.millis(millis));
            pause.setOnFinished(event -> action.run());
            pause.play();
        }
        private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
        private void fail(Throwable failure) {
            failure.printStackTrace();
            stage.close();
            Platform.exit();
            System.exit(1);
        }
    }
}
