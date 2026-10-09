package com.lumenmusic;

/** Avoids the JVM's JavaFX Application main-class lookup for classpath runs. */
public final class Phase3DesktopSmokeLauncher {
    private Phase3DesktopSmokeLauncher() { }

    public static void main(String[] args) {
        System.out.println("Phase3DesktopSmokeLauncher: Java " + System.getProperty("java.version")
                + ", os=" + System.getProperty("os.name") + ", starting JavaFX Application");
        System.out.flush();
        Phase3DesktopSmoke.main(args);
        System.out.println("Phase3DesktopSmokeLauncher: JavaFX Application returned");
    }
}
