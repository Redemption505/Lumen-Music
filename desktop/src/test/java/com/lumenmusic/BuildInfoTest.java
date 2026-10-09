package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class BuildInfoTest {
    @Test void mavenProducesUsableVersionAndBuildMetadata() {
        assertEquals("0.2.2", BuildInfo.current().version());
        assertFalse(BuildInfo.current().buildId().contains("${"));
        assertFalse(BuildInfo.current().buildId().equals("unpackaged"));
        assertTrue(BuildInfo.releaseNotes().contains("片段"));
        assertTrue(BuildInfo.releaseNotes().contains("诊断"));
        assertTrue(BuildInfo.releaseNotes().contains("个人首页"));
    }
    @Test void metadataRejectsUnexpandedAndInvalidFields() {
        assertThrows(IllegalArgumentException.class, () -> new BuildInfo("${project.version}", "build"));
        assertThrows(IllegalArgumentException.class, () -> new BuildInfo("0.2.0", "../private"));
        assertEquals("release-3", new BuildInfo("0.2.0", "release-3").buildId());
    }
}
