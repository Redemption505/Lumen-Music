package com.lumenmusic;

import com.sun.net.httpserver.HttpServer;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Real JavaFX close paths. The driver must also assert that this JVM exits naturally. */
public final class PetExitSmoke extends Application {
    private static Throwable failure;
    private LumenFxApp app;
    private Stage main;
    private DesktopPetWindow desktop;
    private HttpServer service;
    private Path root;
    private String scenario;
    private boolean closeVerified;
    private PetCompanion.Species selectedSpecies;
    private int assertions;

    public static void main(String[] args) {
        launch(args);
        if (failure != null) throw new AssertionError("Pet exit smoke failed", failure);
    }

    @Override public void start(Stage stage) {
        main = stage;
        scenario = getParameters().getRaw().isEmpty() ? "button" : getParameters().getRaw().get(0);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fail(error));
        try {
            verifyProductionOrigin();
            Path preferences = Path.of(System.getenv("LUMEN_PREFERENCES_PATH")).toAbsolutePath();
            root = preferences.getParent();
            check(!Files.exists(preferences), "Smoke requires a fresh isolated preferences file");
            URI url = URI.create(System.getenv("LUMEN_SERVICE_URL"));
            check("127.0.0.1".equals(url.getHost()), "Smoke service must be isolated loopback");
            service = HttpServer.create(new InetSocketAddress("127.0.0.1", url.getPort()), 0);
            service.createContext("/api/", exchange -> {
                String body = switch (exchange.getRequestURI().getPath()) {
                    case "/api/library" -> "<library version=\"1\"><favorites/><playlists/></library>";
                    case "/api/ai-settings" -> "<ai-settings enabled=\"false\" keyConfigured=\"false\"/>";
                    default -> "<tracks/>";
                };
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            service.start();
            app = new LumenFxApp();
            app.startUi(stage, List.of());
            desktop = field(app, "desktopPet", DesktopPetWindow.class);
            after(500, () -> {
                if (scenario.equals("button")) verifyNonExitPaths();
                else prepareExit();
            });
        } catch (Throwable error) { fail(error); }
    }

    private void verifyProductionOrigin() throws Exception {
        String configured = System.getProperty("lumen.smoke.installed.jar");
        if (configured == null || configured.isBlank()) return;
        Path expected = Path.of(configured).toRealPath();
        for (Class<?> production : List.of(LumenFxApp.class, DesktopPetWindow.class, PetCompanion.class)) {
            Path actual = Path.of(production.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            check(expected.equals(actual), "Wrong production origin for " + production.getSimpleName() + ": " + actual);
            System.out.println("PRODUCTION_ORIGIN " + production.getSimpleName() + " " + actual);
        }
    }

    private void verifyNonExitPaths() {
        main.setIconified(true);
        after(300, () -> {
            check(!field(app, "closing", Boolean.class), "Normal minimization must not exit");
            main.setIconified(false);
            button(main.getScene(), "open-mini").fire();
            Stage mini = field(app, "miniStage", Stage.class);
            check(mini != null && mini.isShowing() && !main.isShowing(), "Mini player must hide main window");
            button(mini.getScene(), "mini-close").fire();
            check(main.isShowing() && !field(app, "closing", Boolean.class), "Mini close must restore main window");
            CheckBox immersiveOption = (CheckBox) main.getScene().lookup("#pet-desktop-immersive");
            if (!immersiveOption.isSelected()) immersiveOption.fire();
            button(main.getScene(), "open-immersive").fire();
            after(350, () -> {
                check(desktop.isShowing(), "Immersive mode must show desktop pet");
                button(main.getScene(), "immersive-collapse").fire();
                after(350, () -> {
                    check(!desktop.isShowing() && !field(app, "closing", Boolean.class),
                            "Leaving immersive mode must return pet without exiting");
                    verifyPetCloseRaces();
                    prepareExit();
                });
            });
        });
    }

    private void verifyPetCloseRaces() {
        for (boolean returning : List.of(false, true)) {
            PetCompanion pet = new PetCompanion();
            VBox dock = new VBox(pet);
            Stage host = new Stage();
            host.setScene(new Scene(dock, 160, 150));
            host.show();
            host.getScene().getRoot().applyCss();
            host.getScene().getRoot().layout();
            DesktopPetWindow extra = new DesktopPetWindow(pet, dock, () -> {
                throw new AssertionError("Closed pet must not restore main window");
            });
            extra.showAnimated();
            extra.showNotice("Close while animating");
            if (returning) extra.hideAnimated();
            Animation movement = field(extra, "placementMotion", Animation.class);
            extra.close();
            extra.close();
            extra.show();
            extra.showAnimated();
            extra.hideAnimated();
            extra.showNotice("Late notice");
            pet.setSpecies(PetCompanion.Species.ORANGE_DOG);
            pet.onPlaybackState(true);
            pet.onInteraction("late");
            check(!extra.isShowing() && pet.getParent() == dock, "Closed pet must stay in dock");
            check(!field(extra, "noticePopup", Popup.class).isShowing(), "Close must hide notice popup");
            check(field(extra, "placementMotion", Animation.class) == null, "Close must release placement animation");
            if (movement != null) {
                check(movement.getStatus() == Animation.Status.STOPPED && movement.getOnFinished() == null,
                        "Close must cancel placement completion callback");
            }
            check(pet.species() == PetCompanion.Species.BLACK_CAT, "Late species update must not mutate closed pet");
            check(!pet.isPlaying(), "Late playback callback must not mutate closed pet");
            check(field(pet, "rhythm", Animation.class).getStatus() == Animation.Status.STOPPED,
                    "Closed companion rhythm must stop");
            host.close();
        }
    }

    private void prepareExit() {
        switch (scenario) {
            case "minimized" -> {
                CheckBox option = (CheckBox) main.getScene().lookup("#pet-desktop-minimized");
                if (!option.isSelected()) option.fire();
                main.setIconified(true);
                after(350, this::closeMain);
            }
            case "immersive" -> {
                CheckBox option = (CheckBox) main.getScene().lookup("#pet-desktop-immersive");
                if (!option.isSelected()) option.fire();
                button(main.getScene(), "open-immersive").fire();
                after(350, this::closeMain);
            }
            case "mini" -> {
                desktop.show();
                button(main.getScene(), "open-mini").fire();
                after(100, this::closeMain);
            }
            case "leaving" -> {
                desktop.showAnimated();
                closeMain();
            }
            case "returning" -> {
                desktop.show();
                desktop.hideAnimated();
                closeMain();
            }
            case "button", "native" -> {
                desktop.show();
                closeMain();
            }
            default -> throw new IllegalArgumentException("Unknown close scenario: " + scenario);
        }
    }

    private void closeMain() {
        check(desktop.isShowing(), "Pet must be showing before main close: " + scenario);
        var bounds = Screen.getPrimary().getVisualBounds();
        if (!scenario.equals("leaving") && !scenario.equals("returning"))
            desktop.setPosition(bounds.getMaxX() - 118, bounds.getMaxY() - 118);
        desktop.showNotice("Exit notice");
        check(field(desktop, "noticePopup", Popup.class).isShowing() != scenario.equals("returning"),
                "Only a returning pet must suppress a notice before close");
        PetCompanion pet = field(app, "pet", PetCompanion.class);
        PetCompanion.Species selected = pet.species();
        selectedSpecies = selected;
        Stage mini = field(app, "miniStage", Stage.class);
        if (scenario.equals("native")) Event.fireEvent(main, new WindowEvent(main, WindowEvent.WINDOW_CLOSE_REQUEST));
        else button(main.getScene(), "window-close").fire();
        check(field(app, "closing", Boolean.class), "Main close must trigger application cleanup");
        check(!desktop.isShowing(), "Desktop pet must disappear synchronously on main close");
        check(!field(desktop, "noticePopup", Popup.class).isShowing(), "Exit must hide pet notice");
        check(field(desktop, "placementMotion", Animation.class) == null, "Exit must stop pet placement animation");
        check(field(pet, "closed", Boolean.class), "Exit must dispose companion");
        check(pet.species() == selected, "Exit must preserve pet species preference");
        if (mini != null) {
            check(!mini.isShowing(), "Application exit must close mini window");
            check(!main.isShowing(), "Mini shutdown must not resurrect main window");
        }
        closeVerified = true;
        System.out.println("CLOSE_VERIFIED " + scenario + " assertions=" + assertions);
        // No Platform.exit here: only the production close handler may end this successful run.
    }

    @Override public void stop() {
        try {
            if (failure == null) {
                check(closeVerified, "JavaFX exited before the close assertions completed");
                Properties preferences = new Properties();
                try (var input = Files.newInputStream(Path.of(System.getenv("LUMEN_PREFERENCES_PATH")))) {
                    preferences.load(input);
                }
                check(selectedSpecies.name().equals(preferences.getProperty("pet")), "Exit must preserve saved species");
                Files.writeString(root.resolve("passed.txt"), "PASS " + scenario + " assertions=" + assertions);
                System.out.println("PASS PetExitSmoke " + scenario + " assertions=" + assertions);
            }
        } catch (Throwable error) { failure = error; error.printStackTrace(System.err); }
        finally {
            if (service != null) service.stop(0);
            if (app != null) app.stop();
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException error) { throw new AssertionError(name, error); }
    }

    private Button button(Scene scene, String id) {
        Button button = (Button) scene.lookup("#" + id);
        check(button != null, "Button exists: " + id);
        return button;
    }

    private void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private void after(int millis, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.millis(millis));
        pause.setOnFinished(event -> {
            try { action.run(); } catch (Throwable error) { fail(error); }
        });
        pause.play();
    }

    private void fail(Throwable error) {
        if (failure != null) return;
        failure = error;
        error.printStackTrace(System.err);
        try { if (app != null) app.stop(); }
        catch (Throwable cleanup) { error.addSuppressed(cleanup); }
        if (main != null) main.hide();
        if (service != null) service.stop(0);
        Platform.exit();
    }
}
