package com.lumenmusic;

import javafx.application.Application;

/** Independent launcher avoids Java's special treatment of JavaFX application classes. */
public final class SegmentBatchSmokeLauncher {
    private SegmentBatchSmokeLauncher() { }
    public static void main(String[] args) { Application.launch(SegmentBatchSmoke.class, args); }
}
