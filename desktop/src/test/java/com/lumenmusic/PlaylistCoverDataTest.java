package com.lumenmusic;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistCoverDataTest {
    @TempDir Path root;

    @Test void preservesFullPortraitAndAlphaWhileMakingPortableArtwork() throws Exception {
        BufferedImage source = new BufferedImage(300, 600, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 600; y++) for (int x = 0; x < 300; x++)
            source.setRGB(x, y, x < 150 ? (y < 300 ? 0xffff0000 : 0xff0000ff)
                    : (y < 300 ? 0xff00ff00 : 0x00000000));
        Path image = root.resolve("音乐封面.png"); ImageIO.write(source, "png", image.toFile());
        String data = PlaylistCoverData.fromFile(image);
        assertTrue(data.startsWith("data:image/png;base64,"));
        assertFalse(data.contains(root.toString()));
        byte[] bytes = Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1));
        assertTrue(bytes.length <= 512 * 1024);
        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(bytes));
        assertEquals(128, resized.getWidth()); assertEquals(256, resized.getHeight());
        assertEquals(0xffff0000, resized.getRGB(0, 0));
        assertEquals(0xff00ff00, resized.getRGB(127, 0));
        assertEquals(0xff0000ff, resized.getRGB(0, 255));
        assertEquals(0, resized.getRGB(127, 255) >>> 24);
    }

    @Test void unreadableImageFailsWithoutChangingOriginal() throws Exception {
        Path image = root.resolve("broken.png"); byte[] original = {1, 2, 3, 4}; Files.write(image, original);
        assertThrows(IOException.class, () -> PlaylistCoverData.fromFile(image));
        assertArrayEquals(original, Files.readAllBytes(image));
    }
}
