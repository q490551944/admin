package com.hpj.admin.chat;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;

@Component
public class ChatImageValidator {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final long MAX_PIXELS = 40_000_000L;
    public static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    public record Validated(String contentType, int width, int height, String sha256, byte[] thumbnail) {}

    public Validated validate(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new ChatException(413, "IMAGE_SIZE_LIMIT", "图片大小须为 1 字节至 10 MB");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String type = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "gif" -> "image/gif";
                    case "webp" -> "image/webp";
                    default -> throw invalid();
                };
                int width = reader.getWidth(0), height = reader.getHeight(0);
                int displayWidth = width, displayHeight = height;
                if (type.equals("image/gif")) {
                    // GIF readers report the frame dimensions, which can be smaller than the
                    // logical canvas the browser allocates when displaying the original.
                    int canvasWidth = Byte.toUnsignedInt(bytes[6]) | Byte.toUnsignedInt(bytes[7]) << 8;
                    int canvasHeight = Byte.toUnsignedInt(bytes[8]) | Byte.toUnsignedInt(bytes[9]) << 8;
                    if (canvasWidth <= 0 || canvasHeight <= 0 || (long) canvasWidth * canvasHeight > MAX_PIXELS)
                        throw new ChatException(413, "IMAGE_PIXEL_LIMIT", "图片像素不能超过 4000 万");
                    displayWidth = canvasWidth;
                    displayHeight = canvasHeight;
                }
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS)
                    throw new ChatException(413, "IMAGE_PIXEL_LIMIT", "图片像素不能超过 4000 万");
                // Validate the encoded first frame before accepting it. Originals retain animation;
                // thumbnail decoding never iterates an unbounded animation frame count.
                BufferedImage original = reader.read(0);
                if (original == null) throw invalid();
                double scale = Math.min(1.0, 480.0 / Math.max(width, height));
                BufferedImage thumbnail = new BufferedImage(Math.max(1, (int)(width * scale)),
                        Math.max(1, (int)(height * scale)), BufferedImage.TYPE_INT_ARGB);
                var graphics = thumbnail.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(original, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null);
                } finally { graphics.dispose(); original.flush(); }
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(thumbnail, "png", output)) throw invalid();
                thumbnail.flush();
                return new Validated(type, displayWidth, displayHeight,
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), output.toByteArray());
            } finally { reader.dispose(); }
        } catch (ChatException error) { throw error; }
        catch (Exception error) { throw invalid(); }
    }

    private static ChatException invalid() {
        return new ChatException(415, "INVALID_IMAGE", "文件不是有效的 JPEG、PNG、GIF 或 WebP 图片");
    }
}
