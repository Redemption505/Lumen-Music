package com.lumenmusic;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Run as a JavaFX application to exercise the actual transparent pet window. */
public final class PetDesktopSmoke extends Application {
    private static final Path OUTPUT = Path.of("../build/pet-desktop-smoke");
    private static Throwable failure;
    private DesktopPetWindow window;
    private PetCompanion pet;
    private VBox dock;
    private int[] firstFrame;
    private int restoreCalls;

    public static void main(String[] args) {
        launch(args);
        if (failure != null) throw new AssertionError("Pet desktop smoke failed", failure);
    }

    @Override public void start(Stage unused) {
        try {
            Files.createDirectories(OUTPUT);
            pet = new PetCompanion();
            dock = new VBox(pet);
            window = new DesktopPetWindow(pet, dock, () -> {
                restoreCalls++;
                window.hideToDock();
            });
            window.show();
            check(window.isShowing() && pet.getParent() != dock, "window did not detach pet");
            after(350, () -> {
                pet.getScene().getRoot().applyCss();
                WritableImage windowImage = capture(pet.getScene().getRoot(), "pet-window.png");
                check(windowImage.getPixelReader().getColor(0, 0).getOpacity() < .05,
                        "transparent window root has an opaque corner");
                firstFrame = snapshot("pet-first.png");
                after(1300, () -> {
                    int[] second = snapshot("pet-idle.png");
                    check(nontransparent(second) > 1000, "pet graphic is blank");
                    check(difference(firstFrame, second) > 100, "idle animation did not change pixels");
                    pet.setSpecies(PetCompanion.Species.RICE_GHOST);
                    check(nontransparent(snapshot("pet-ghost.png")) > 1000, "ghost graphic is blank");
                    pet.setSpecies(PetCompanion.Species.ORANGE_DOG);
                    check(nontransparent(snapshot("pet-dog.png")) > 1000, "dog graphic is blank");
                    checkMouseInteractions();
                });
            });
        } catch (Throwable error) { fail(error); }
    }

    private void checkMouseInteractions() {
        var screen = Screen.getPrimary().getVisualBounds();
        window.setPosition(screen.getMinX() + screen.getWidth() / 2,
                screen.getMinY() + screen.getHeight() / 2);
        double x = window.getX(), y = window.getY();
        double pointerX = x + 40, pointerY = y + 40;
        mouse(MouseEvent.MOUSE_PRESSED, pointerX, pointerY, 1);
        mouse(MouseEvent.MOUSE_RELEASED, pointerX, pointerY, 1);
        mouse(MouseEvent.MOUSE_CLICKED, pointerX, pointerY, 1);
        check(window.getX() == x && window.getY() == y, "short click moved window");
        after(110, () -> {
            check(pet.getChildren().get(0).getTranslateY() < -1, "click did not start reaction");
            check(restoreCalls == 0, "single click restored main window");
            mouse(MouseEvent.MOUSE_PRESSED, pointerX, pointerY, 1);
            after(290, () -> {
                mouse(MouseEvent.MOUSE_DRAGGED, pointerX + 35, pointerY + 25, 1);
                mouse(MouseEvent.MOUSE_RELEASED, pointerX + 35, pointerY + 25, 1);
                mouse(MouseEvent.MOUSE_CLICKED, pointerX + 35, pointerY + 25, 1);
                check(window.getX() > x + 20 && window.getY() > y + 10,
                        "long press drag did not move window");
                check(restoreCalls == 0, "drag triggered restore");
                double newX = window.getX() + 40, newY = window.getY() + 40;
                mouse(MouseEvent.MOUSE_PRESSED, newX, newY, 2);
                mouse(MouseEvent.MOUSE_RELEASED, newX, newY, 2);
                mouse(MouseEvent.MOUSE_CLICKED, newX, newY, 2);
                check(restoreCalls == 1 && !window.isShowing() && pet.getParent() == dock,
                        "double click did not restore dock");
                window.show();
                check(window.isShowing() && pet.getParent() != dock, "pet did not reopen");
                window.setPosition(-100000, -100000);
                check(window.getX() >= screen.getMinX() && window.getY() >= screen.getMinY(),
                        "window position not clamped");
                window.close();
                check(!window.isShowing() && pet.getParent() == dock, "close did not clean up");
                Platform.exit();
            });
        });
    }

    private void mouse(javafx.event.EventType<MouseEvent> type, double screenX, double screenY, int clicks) {
        pet.fireEvent(new MouseEvent(type, 40, 40, screenX, screenY, MouseButton.PRIMARY, clicks,
                false, false, false, false, true, false, false, false, false, true, null));
    }

    private int[] snapshot(String fileName) {
        WritableImage image = capture(pet, fileName);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            Color color = image.getPixelReader().getColor(x, y);
            pixels[y * width + x] = ((int) Math.round(color.getOpacity() * 255) << 24)
                    | ((int) Math.round(color.getRed() * 255) << 16)
                    | ((int) Math.round(color.getGreen() * 255) << 8)
                    | (int) Math.round(color.getBlue() * 255);
        }
        return pixels;
    }

    private WritableImage capture(Node node, String fileName) {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        WritableImage image = node.snapshot(parameters, null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            Color color = image.getPixelReader().getColor(x, y);
            int argb = ((int) Math.round(color.getOpacity() * 255) << 24)
                    | ((int) Math.round(color.getRed() * 255) << 16)
                    | ((int) Math.round(color.getGreen() * 255) << 8)
                    | (int) Math.round(color.getBlue() * 255);
            output.setRGB(x, y, argb);
        }
        try { ImageIO.write(output, "png", OUTPUT.resolve(fileName).toFile()); }
        catch (Exception error) { throw new RuntimeException(error); }
        return image;
    }

    private static long nontransparent(int[] pixels) {
        return java.util.Arrays.stream(pixels).filter(pixel -> (pixel >>> 24) > 40).count();
    }

    private static long difference(int[] a, int[] b) {
        check(a.length == b.length, "snapshot size changed");
        long changed = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) changed++;
        return changed;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void after(double millis, Runnable action) {
        PauseTransition wait = new PauseTransition(Duration.millis(millis));
        wait.setOnFinished(event -> {
            try { action.run(); }
            catch (Throwable error) { fail(error); }
        });
        wait.play();
    }

    private void fail(Throwable error) {
        failure = error;
        if (window != null) window.close();
        Platform.exit();
    }
}
