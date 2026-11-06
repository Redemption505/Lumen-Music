package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.Properties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ReleaseProfileTest {
    @Test void packagedReleaseProfileMatchesTheFilteredBuildProperty() throws Exception {
        Properties properties = new Properties();
        try (var input = ReleaseProfile.class.getResourceAsStream("/lumen-build.properties")) {
            assertNotNull(input);
            properties.load(input);
        }
        assertEquals(properties.getProperty("release.profile"), ReleaseProfile.current().id());
    }

    @Test void profileValuesAndPathsKeepFullDataSeparateFromLocalPreview() {
        ReleaseProfile full = ReleaseProfile.from("full");
        ReleaseProfile local = ReleaseProfile.from("local-first");

        assertEquals("full", ReleaseProfile.from(null).id());
        assertEquals("local-first", local.id());
        assertEquals("local", local.provider());
        assertEquals(18765, local.servicePort());
        assertEquals(18766, local.streamPort());
        assertEquals("Lumen Music Local Preview", local.appDataDirectory().getFileName().toString());
        assertEquals(local.appDataDirectory(), local.dataDirectory());
        assertEquals(local.appDataDirectory(), local.preferencesPath().getParent());

        assertEquals("Lumen Music", full.appDataDirectory().getFileName().toString());
        assertEquals(8765, full.servicePort());
        assertEquals(8766, full.streamPort());
        assertThrows(IllegalArgumentException.class, () -> ReleaseProfile.from("other"));
    }

    @Test void localFirstAudioPolicyAllowsFileUrisAndRejectsNetworkUrisIncludingSegments() {
        ReleaseProfile local = ReleaseProfile.from("local-first");
        Track online = new Track("bilibili:BV123:p1", "在线条目", "歌手", 120,
                "bilibili", URI.create("https://example.test/audio.m4a"), "BV123", 1);
        Track onlineSegment = Track.segment(online, "片段", "歌手", 1_000, 3_000);

        assertTrue(local.allowsAudioUri(URI.create("file:///music/song.flac")));
        assertFalse(local.allowsAudioUri(online.mediaUri()));
        assertFalse(local.allowsAudioUri(onlineSegment.audioSource().mediaUri()));
        assertFalse(local.allowsAudioUri(null));
        assertTrue(ReleaseProfile.from("full").allowsAudioUri(online.mediaUri()));
    }

    @Test void localFirstBlocksHttpCoverLoadsButFullKeepsThem() {
        URI file = URI.create("file:///music/cover.png");
        URI http = URI.create("http://example.test/cover.png");
        URI https = URI.create("https://example.test/cover.png");

        assertTrue(CoverCache.profileAllows(file, ReleaseProfile.from("local-first")));
        assertFalse(CoverCache.profileAllows(http, ReleaseProfile.from("local-first")));
        assertFalse(CoverCache.profileAllows(https, ReleaseProfile.from("local-first")));
        assertTrue(CoverCache.profileAllows(https, ReleaseProfile.from("full")));
    }
}
