package com.lumenmusic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

/** Computed styles of real application controls, evaluated by the scene smoke drivers. */
final class FrontendStyleAssertions {
    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");
    private static final PseudoClass FOCUSED = PseudoClass.getPseudoClass("focused");
    private static final PseudoClass DISABLED = PseudoClass.getPseudoClass("disabled");

    static void home(Scene scene) {
        Map<String, Paint> palette = palette(scene);
        buttonStates(button(scene, "home-open-organization"), palette, true);
        buttonStates(button(scene, "home-open-diary"), palette, true);
        Set<Node> pins = scene.getRoot().lookupAll(".home-pin-button");
        require(pins.size() >= 2, "ordinary and smart pins must be present for style acceptance");
        for (Node pin : pins) buttonStates((ButtonBase) pin, palette, false);
        ButtonBase collapse = button(scene, "sidebar-collapse");
        Set<PseudoClass> original = Set.copyOf(collapse.getPseudoClassStates());
        try {
            collapse.pseudoClassStateChanged(HOVER, false); collapse.pseudoClassStateChanged(FOCUSED, false); collapse.applyCss();
            require(background(collapse).equals(palette.get("panel")), "sidebar collapse background");
            require(collapse.getBorder().getStrokes().get(0).getTopStroke().equals(palette.get("divider")), "sidebar collapse divider");
            collapse.pseudoClassStateChanged(HOVER, true); collapse.applyCss();
            require(background(collapse).equals(palette.get("hover")), "sidebar collapse hover");
            focusAndDisabled(collapse, palette);
        } finally { restore(collapse, original); }
    }

    static void playlist(Scene scene) {
        Node actions = scene.lookup("#playlist-header-actions");
        require(actions != null, "playlist action container is not addressable");
        Set<Node> buttons = actions.lookupAll(".button");
        require(buttons.size() >= 4, "playlist action style matched no usable controls");
        Map<String, Paint> palette = palette(scene);
        for (Node node : buttons) {
            ButtonBase button = (ButtonBase) node;
            require(button.getPadding().equals(new Insets(7, 12, 7, 12)), "playlist padding " + button.getId());
            require(button.getBackground().getFills().get(0).getRadii().getTopLeftHorizontalRadius() == 8,
                    "playlist corner radius " + button.getId());
            focusAndDisabled(button, palette);
        }
    }

    static void mini(Scene scene) {
        ButtonBase button = button(scene, "mini-return");
        Map<String, Paint> palette = palette(scene);
        require(!button.isFocused(), "mini baseline must not own input focus");
        button.applyCss();
        Paint before = button.getBorder().getStrokes().get(0).getTopStroke();
        focusAndDisabled(button, palette);
        // Check hover and focus together too: an ID hover rule must not hide focus.
        Set<PseudoClass> original = Set.copyOf(button.getPseudoClassStates());
        try {
            button.pseudoClassStateChanged(HOVER, true);
            button.pseudoClassStateChanged(FOCUSED, true);
            button.applyCss();
            require(background(button).equals(palette.get("hover")), "mini hover background");
            require(button.getBorder().getStrokes().get(0).getTopStroke().equals(palette.get("focus")), "mini hover hides focus");
        } finally { restore(button, original); }
        require(!before.equals(palette.get("focus")), "mini base and focus border must be distinguishable");
    }

    private static void buttonStates(ButtonBase button, Map<String, Paint> palette, boolean auxiliary) {
        Set<PseudoClass> original = Set.copyOf(button.getPseudoClassStates());
        try {
            button.pseudoClassStateChanged(HOVER, false);
            button.pseudoClassStateChanged(FOCUSED, false);
            button.applyCss();
            Paint fill = background(button);
            require(auxiliary ? fill instanceof Color color && color.getOpacity() == 0 : fill.equals(palette.get("field")),
                    "wrong home background " + button.getId() + ": " + fill);
            require(button.getTextFill().equals(palette.get(auxiliary ? "muted" : "text")), "wrong home text " + button.getId());
            if (auxiliary) require(button.getBorder().getStrokes().get(0).getTopStroke().equals(palette.get("divider")),
                    "missing auxiliary border " + button.getId());
            button.pseudoClassStateChanged(HOVER, true); button.applyCss();
            require(background(button).equals(palette.get("hover")), "wrong hover background " + button.getId());
            require(button.getTextFill().equals(palette.get("text")), "wrong hover text " + button.getId());
            focusAndDisabled(button, palette);
        } finally { restore(button, original); }
    }

    private static void focusAndDisabled(ButtonBase button, Map<String, Paint> palette) {
        Set<PseudoClass> original = Set.copyOf(button.getPseudoClassStates());
        boolean disabled = button.isDisable();
        boolean bound = button.disableProperty().isBound();
        var bounds = button.getLayoutBounds();
        try {
            button.pseudoClassStateChanged(FOCUSED, true); button.applyCss();
            require(button.getBorder().getStrokes().get(0).getTopStroke().equals(palette.get("focus")), "missing focus border " + button.getId()
                    + ": " + button.getBorder().getStrokes().get(0).getTopStroke() + ", expected " + palette.get("focus"));
            require(bounds.equals(button.getLayoutBounds()), "focus changed geometry " + button.getId());
            // Some real controls bind disable to queue/selection state. Probe the CSS
            // pseudo-class without breaking that binding or mutating application state.
            button.pseudoClassStateChanged(DISABLED, true); button.applyCss();
            require(Math.abs(button.getOpacity() - .55) < .001, "disabled style lost " + button.getId());
            require(button.isDisable() == disabled && button.disableProperty().isBound() == bound,
                    "style probe changed disable binding " + button.getId());
        } finally { restore(button, original); }
    }

    private static void restore(ButtonBase button, Set<PseudoClass> states) {
        button.pseudoClassStateChanged(HOVER, states.contains(HOVER));
        button.pseudoClassStateChanged(FOCUSED, states.contains(FOCUSED));
        button.pseudoClassStateChanged(DISABLED, states.contains(DISABLED));
        button.applyCss();
    }

    /** Resolve the live theme's semantic colors, without hardcoding a second palette in the test. */
    private static Map<String, Paint> palette(Scene scene) {
        Pane parent = (Pane) scene.getRoot();
        Region sample = new Region(); sample.setManaged(false); sample.setVisible(false);
        parent.getChildren().add(sample);
        try {
            Map<String, Paint> colors = new HashMap<>();
            for (String role : List.of("field", "muted", "divider", "hover", "text", "focus", "panel")) {
                sample.setStyle("-fx-background-color: -lumen-" + role + ";"); sample.applyCss();
                colors.put(role, background(sample));
            }
            return colors;
        } finally { parent.getChildren().remove(sample); }
    }

    private static Paint background(Region region) {
        require(region.getBackground() != null && !region.getBackground().getFills().isEmpty(), "missing rendered background");
        return region.getBackground().getFills().get(0).getFill();
    }
    private static ButtonBase button(Scene scene, String id) {
        Node value = scene.lookup("#" + id);
        require(value instanceof ButtonBase, "missing button " + id);
        return (ButtonBase) value;
    }
    private static void require(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
}
