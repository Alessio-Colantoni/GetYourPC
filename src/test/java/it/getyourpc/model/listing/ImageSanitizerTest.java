package it.getyourpc.model.listing;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageSanitizerTest {
    private final ImageSanitizer sanitizer = new ImageSanitizer();

    @Test
    void acceptsRealPngEvenWhenDeclaredMimeIsSpoofedAndReencodesIt() throws Exception {
        byte[] original = image("png", BufferedImage.TYPE_INT_ARGB);
        byte[] marker = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        byte[] polyglot = Arrays.copyOf(original, original.length + marker.length);
        System.arraycopy(marker, 0, polyglot, original.length, marker.length);
        MockMultipartFile upload = new MockMultipartFile("photos", "image.html", "text/html", polyglot);

        PhotoData sanitized = sanitizer.sanitize(upload);

        assertEquals("image/png", sanitized.contentType());
        assertTrue(sanitized.bytes().length > 8);
        assertFalse(new String(sanitized.bytes(), StandardCharsets.ISO_8859_1).contains("<script>"));
        assertNotEquals("text/html", sanitized.contentType());
        assertTrue(sanitizer.safeStoredImage(sanitized.bytes()).isPresent());
    }

    @Test
    void acceptsAndReencodesRealJpeg() throws Exception {
        byte[] original = image("jpeg", BufferedImage.TYPE_INT_RGB);

        PhotoData sanitized = sanitizer.sanitize(
                new MockMultipartFile("photos", "photo.jpg", "image/jpeg", original));

        assertEquals("image/jpeg", sanitized.contentType());
        assertTrue(sanitized.bytes()[0] == (byte) 0xff && sanitized.bytes()[1] == (byte) 0xd8);
    }

    @Test
    void rejectsHtmlEvenWhenDeclaredAsPng() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> sanitizer.sanitize(new MockMultipartFile("photos", "attack.png", "image/png", html)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertFalse(sanitizer.safeStoredImage(html).isPresent());
    }

    @Test
    void rejectsSvgAndUnsupportedRasterFormats() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] gif = image("gif", BufferedImage.TYPE_INT_RGB);

        assertThrows(ResponseStatusException.class, () -> sanitizer.sanitize(
                new MockMultipartFile("photos", "attack.svg", "image/svg+xml", svg)));
        assertThrows(ResponseStatusException.class, () -> sanitizer.sanitize(
                new MockMultipartFile("photos", "animation.gif", "image/gif", gif)));
    }

    @Test
    void rejectsFilesOverTheConfiguredByteLimitBeforeDecoding() {
        byte[] oversized = new byte[Math.toIntExact(ImageSanitizer.MAX_FILE_SIZE + 1)];

        assertThrows(ResponseStatusException.class, () -> sanitizer.sanitize(
                new MockMultipartFile("photos", "large.png", "image/png", oversized)));
    }

    @Test
    void rejectsExcessivePixelCountBeforeAllocatingTheRaster() throws Exception {
        byte[] oversizedDimensions = pngHeader(5_000, 5_000);

        assertThrows(ResponseStatusException.class, () -> sanitizer.sanitize(
                new MockMultipartFile("photos", "bomb.png", "image/png", oversizedDimensions)));
    }

    @Test
    void rejectsConcurrentRasterProcessingWithoutAllocatingAnotherImage() throws Exception {
        ImageSanitizer busySanitizer = new ImageSanitizer(new Semaphore(0), Duration.ZERO);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> busySanitizer.sanitize(new MockMultipartFile(
                        "photos", "computer.png", "image/png", image("png", BufferedImage.TYPE_INT_RGB))));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
    }

    private static byte[] image(String format, int type) throws Exception {
        BufferedImage image = new BufferedImage(24, 18, type);
        image.setRGB(4, 5, Color.ORANGE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }

    private static byte[] pngHeader(int width, int height) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
            byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
            byte[] header = ByteBuffer.allocate(13)
                    .putInt(width).putInt(height)
                    .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0)
                    .array();
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(header);
            data.writeInt(header.length);
            data.write(type);
            data.write(header);
            data.writeInt((int) crc.getValue());
        }
        return output.toByteArray();
    }
}
