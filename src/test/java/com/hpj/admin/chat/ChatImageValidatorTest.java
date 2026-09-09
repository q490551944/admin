package com.hpj.admin.chat;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChatImageValidatorTest {
    private final ChatImageValidator validator = new ChatImageValidator();

    @Test void acceptsRealWebpAndProducesPngThumbnail() throws Exception {
        try (var input = getClass().getResourceAsStream("/chat/sample.webp")) {
            assertThat(input).isNotNull();
            var result = validator.validate(input.readAllBytes());
            assertThat(result.contentType()).isEqualTo("image/webp");
            assertThat(result.width()).isEqualTo(16);
            assertThat(result.height()).isEqualTo(8);
            assertThat(result.sha256()).hasSize(64);
            assertThat(result.thumbnail()).startsWith((byte)137, (byte)80, (byte)78, (byte)71);
        }
    }

    @Test void rejectsDecompressionBombDimensionsBeforePixelAllocation() throws Exception {
        byte[] png = ChatAttachmentIntegrationTest.image("png");
        ByteBuffer.wrap(png).putInt(16, 100_000).putInt(20, 100_000);
        var crc = new CRC32(); crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int)crc.getValue());
        assertThatThrownBy(() -> validator.validate(png)).isInstanceOf(ChatException.class)
                .extracting(error -> ((ChatException)error).getCode()).isEqualTo("IMAGE_PIXEL_LIMIT");
    }

    @Test void rejectsEmptyCorruptAndUnsupportedContents() {
        for (byte[] bytes : new byte[][] {new byte[0], new byte[]{1,2,3,4}, "<svg/>".getBytes()})
            assertThatThrownBy(() -> validator.validate(bytes)).isInstanceOf(ChatException.class);
    }

    @Test void rejectsOversizedGifCanvasEvenWhenItsFirstFrameIsTiny() {
        byte[] gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
        ByteBuffer.wrap(gif).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(6, (short)10_000).putShort(8, (short)10_000);
        assertThatThrownBy(() -> validator.validate(gif)).isInstanceOf(ChatException.class)
                .extracting(error -> ((ChatException)error).getCode()).isEqualTo("IMAGE_PIXEL_LIMIT");
        ByteBuffer.wrap(gif).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(6, (short)2).putShort(8, (short)3);
        var accepted = validator.validate(gif);
        assertThat(accepted.width()).isEqualTo(2);
        assertThat(accepted.height()).isEqualTo(3);
    }
}
