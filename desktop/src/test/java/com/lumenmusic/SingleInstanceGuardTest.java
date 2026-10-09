package com.lumenmusic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceGuardTest {
    @TempDir Path temp;

    @Test void preventsSecondJvmAndReleasesLockOnClose() throws Exception {
        SingleInstanceGuard first = SingleInstanceGuard.acquire(temp);
        assertNotNull(first);
        try {
            assertNull(SingleInstanceGuard.acquire(temp));
        } finally {
            first.close();
        }
        try (SingleInstanceGuard restarted = SingleInstanceGuard.acquire(temp)) {
            assertNotNull(restarted);
        }
    }
}
