package com.lumenmusic;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Window;

/** Rendered focus checks require real input focus, not merely Scene.focusOwner. */
final class FocusPixelAssertions {
    record Baseline(WritableImage pixels, Bounds bounds) { }

    static Baseline capture(Node anchor, Node target) {
        requireFocused(anchor);
        require(anchor.getScene() == target.getScene(), "FOCUS_SCENE_MISMATCH");
        require(!target.isFocused(), "FOCUS_BASELINE_ALREADY_FOCUSED " + state(target));
        target.getScene().getRoot().applyCss();
        target.getScene().getRoot().layout();
        return new Baseline(target.snapshot(null, null), target.getBoundsInLocal());
    }

    static int verify(Node target, Baseline baseline) {
        requireFocused(target);
        target.getScene().getRoot().applyCss();
        target.getScene().getRoot().layout();
        require(baseline.bounds().equals(target.getBoundsInLocal()), "FOCUS_CHANGED_BOUNDS " + state(target));
        WritableImage after = target.snapshot(null, null);
        int changes = perimeterChanges(baseline.pixels(), after);
        require(changes >= 20, "FOCUS_BORDER_MISSING pixels=" + changes + " " + state(target));
        System.out.println("PASS focus pixels=" + changes + " " + state(target));
        return changes;
    }

    static int perimeterChanges(WritableImage before, WritableImage after) {
        int width = (int) after.getWidth(), height = (int) after.getHeight();
        require(width == (int) before.getWidth() && height == (int) before.getHeight(), "FOCUS_CHANGED_IMAGE_SIZE");
        int changed = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (x > 3 && x < width - 4 && y > 3 && y < height - 4) continue;
            if (after.getPixelReader().getArgb(x, y) != before.getPixelReader().getArgb(x, y)) changed++;
        }
        return changed;
    }

    static void requireFocused(Node target) {
        Scene scene = target.getScene();
        Window window = scene == null ? null : scene.getWindow();
        require(window != null && window.isShowing() && window.isFocused(), "FOCUS_WINDOW_INACTIVE " + state(target));
        require(scene.getFocusOwner() == target && target.isFocused(), "FOCUS_TARGET_NOT_FOCUSED " + state(target));
    }

    static String state(Node target) {
        Scene scene = target.getScene();
        Window window = scene == null ? null : scene.getWindow();
        Node owner = scene == null ? null : scene.getFocusOwner();
        return "target=" + target.getId() + ", owner=" + (owner == null ? "null" : owner.getId())
                + ", windowFocused=" + (window != null && window.isFocused())
                + ", nodeFocused=" + target.isFocused() + ", pseudo=" + target.getPseudoClassStates();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
