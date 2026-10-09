package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.HICON;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class WindowsTaskbarControlsTest {
    @Test void routesOnlyKnownThumbnailCommands() {
        Runnable previous = () -> {};
        Runnable toggle = () -> {};
        Runnable next = () -> {};
        Runnable favorite = () -> {};
        assertSame(previous, WindowsTaskbarControls.commandFor(41, previous, toggle, next, favorite));
        assertSame(toggle, WindowsTaskbarControls.commandFor(42, previous, toggle, next, favorite));
        assertSame(next, WindowsTaskbarControls.commandFor(43, previous, toggle, next, favorite));
        assertSame(favorite, WindowsTaskbarControls.commandFor(44, previous, toggle, next, favorite));
        assertNull(WindowsTaskbarControls.commandFor(45, previous, toggle, next, favorite));
        long wParam = ((long) 0x1800 << 16) | 42;
        assertEquals(0x1800, WindowsTaskbarControls.highWord(wParam));
        assertEquals(42, WindowsTaskbarControls.lowWord(wParam));
    }

    @Test void favoriteButtonTracksSelection() {
        HICON outline = new HICON();
        HICON filled = new HICON();
        WindowsTaskbarControls.ThumbButton button = new WindowsTaskbarControls.ThumbButton();
        WindowsTaskbarControls.configureFavorite(button, false, outline, filled);
        assertEquals(44, button.iId);
        assertSame(outline, button.hIcon);
        assertEquals("收藏", Native.toString(button.szTip));
        WindowsTaskbarControls.configureFavorite(button, true, outline, filled);
        assertSame(filled, button.hIcon);
        assertEquals("取消收藏", Native.toString(button.szTip));
    }

    @Test void thumbnailButtonMatchesWin32MemoryLayout() {
        // THUMBBUTTON: DWORD, UINT, UINT, pointer, WCHAR[260], DWORD.
        assertEquals(Native.POINTER_SIZE == 8 ? 552 : 540,
                new WindowsTaskbarControls.ThumbButton().size());
    }

    @Test void transportGlyphsMatchPlayerDirectionAndDifferFromPause() {
        var previous = WindowsTaskbarControls.iconBits(WindowsTaskbarControls.IconKind.PREVIOUS);
        var play = WindowsTaskbarControls.iconBits(WindowsTaskbarControls.IconKind.PLAY);
        var next = WindowsTaskbarControls.iconBits(WindowsTaskbarControls.IconKind.NEXT);
        var pause = WindowsTaskbarControls.iconBits(WindowsTaskbarControls.IconKind.PAUSE);
        assertTrue(on(previous, 3, 7));
        assertTrue(on(previous, 4, 7));
        assertTrue(on(next, 12, 7));
        assertTrue(on(next, 11, 7));
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            assertEquals(on(previous, x, y), on(next, 15 - x, y), "mirror at " + x + "," + y);
        }
        assertTrue(on(previous, 11, 3));
        assertFalse(on(previous, 4, 3));
        assertTrue(on(next, 4, 3));
        assertFalse(on(next, 11, 3));
        assertTrue(on(play, 4, 3), "play has a broad left edge");
        assertTrue(on(play, 11, 7), "play points right");
        assertFalse(on(play, 11, 3), "play has a narrow right tip");
        assertFalse(on(pause, 3, 7));
        assertTrue(on(pause, 9, 7));
        assertTrue(on(pause, 9, 3));
        assertFalse(on(previous, 9, 3));
        assertFalse(on(next, 9, 3));
    }

    @Test void heartHasLobesAboveItsPointInEncodedScanlines() {
        for (var kind : new WindowsTaskbarControls.IconKind[] {
                WindowsTaskbarControls.IconKind.FAVORITE_OUTLINE,
                WindowsTaskbarControls.IconKind.FAVORITE_FILLED }) {
            var bits = WindowsTaskbarControls.iconBits(kind);
            assertTrue(on(bits, 4, 2), kind + " left lobe");
            assertTrue(on(bits, 11, 2), kind + " right lobe");
            assertFalse(on(bits, 7, 2), kind + " top notch");
            assertTrue(on(bits, 7, 12), kind + " bottom point");
            assertFalse(on(bits, 4, 12), kind + " narrow bottom");
        }
    }

    @Test void exportsEncodedIconProbe() throws IOException {
        var kinds = WindowsTaskbarControls.IconKind.values();
        BufferedImage image = new BufferedImage(kinds.length * 16 * 8, 16 * 8, BufferedImage.TYPE_INT_ARGB);
        for (int index = 0; index < kinds.length; index++) {
            var bits = WindowsTaskbarControls.iconBits(kinds[index]);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int color = on(bits, x, y) ? 0xffffffff : 0xff30343e;
                for (int sy = 0; sy < 8; sy++) for (int sx = 0; sx < 8; sx++)
                    image.setRGB(index * 128 + x * 8 + sx, y * 8 + sy, color);
            }
        }
        Path output = Path.of("target", "taskbar-icon-probe.png");
        Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(image, "png", output.toFile()));
    }

    private static boolean on(WindowsTaskbarControls.IconBits bits, int x, int y) {
        int colorOffset = (y * 16 + x) * 4;
        int maskOffset = y * 2 + x / 8;
        boolean color = (bits.xorBits()[colorOffset + 3] & 0xff) == 0xff;
        boolean opaque = (bits.andBits()[maskOffset] & (0x80 >>> (x % 8))) == 0;
        assertEquals(color, opaque, "color/mask mismatch at " + x + "," + y);
        return color;
    }
}
