package com.lumenmusic;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/** Shared motion preference. Read and update on the JavaFX application thread. */
public final class MotionPreferences {
    private static final BooleanProperty REDUCED = new SimpleBooleanProperty(false);

    private MotionPreferences() { }

    public static boolean reduced() { return REDUCED.get(); }

    public static void setReduced(boolean reduced) { REDUCED.set(reduced); }

    public static ReadOnlyBooleanProperty reducedProperty() { return REDUCED; }
}
