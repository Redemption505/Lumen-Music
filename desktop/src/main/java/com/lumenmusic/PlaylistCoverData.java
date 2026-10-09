package com.lumenmusic;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Small, self-contained playlist artwork that travels with the library backup. */
final class PlaylistCoverData {
    private PlaylistCoverData() { }

    static String fromFile(Path path) throws IOException {
        if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("请选择不超过 16 MiB 的 PNG 或 JPEG 图片");
        BufferedImage source = ImageIO.read(path.toFile());
        if (source == null) throw new IOException("无法读取这张图片，请选择 PNG 或 JPEG");
        double scale = Math.min(1, 256.0 / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(target, "png", bytes)) throw new IOException("无法保存这张图片");
        if (bytes.size() > 512 * 1024) throw new IOException("封面过大，请选择较小的图片");
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
