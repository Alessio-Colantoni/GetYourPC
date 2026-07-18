package it.getyourpc.model.listing;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class ImageSanitizer {
    static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    static final long MAX_PIXELS = 12_000_000L;
    static final int MAX_DIMENSION = 6_000;

    private static final String INVALID_IMAGE_MESSAGE =
            "Sono accettate solo immagini JPEG o PNG valide, massimo 5 MB e 12 megapixel";
    private static final Duration PROCESSING_WAIT = Duration.ofSeconds(3);

    private final Semaphore processingSlots;
    private final Duration processingWait;

    public ImageSanitizer() {
        this(new Semaphore(1, true), PROCESSING_WAIT);
    }

    ImageSanitizer(Semaphore processingSlots, Duration processingWait) {
        this.processingSlots = Objects.requireNonNull(processingSlots);
        this.processingWait = Objects.requireNonNull(processingWait);
        if (processingWait.isNegative()) throw new IllegalArgumentException("Timeout immagine non valido");
    }

    public PhotoData sanitize(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalidImage();
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw invalidImage();
        }

        boolean acquired = false;
        try {
            acquired = processingSlots.tryAcquire(processingWait.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Elaborazione immagini occupata. Riprova tra qualche secondo");
            }

            InputStream input = file.getInputStream();
            try (input) {
                byte[] source = input.readNBytes(Math.toIntExact(MAX_FILE_SIZE + 1));
                if (source.length == 0 || source.length > MAX_FILE_SIZE) {
                    throw invalidImage();
                }
                DecodedImage decoded = decode(source);
                return encode(decoded);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Elaborazione immagini temporaneamente non disponibile");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidImage();
        } finally {
            if (acquired) processingSlots.release();
        }
    }

    public Optional<PhotoData> safeStoredImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_SIZE) {
            return Optional.empty();
        }

        try {
            ImageFormat format = inspect(bytes);
            return Optional.of(new PhotoData(bytes, format.contentType));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static DecodedImage decode(byte[] source) throws IOException {
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }

            ImageReader reader = readers.next();
            try {
                ImageFormat format = ImageFormat.from(reader.getFormatName());
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);

                BufferedImage image = reader.read(0);
                if (image == null || image.getWidth() != width || image.getHeight() != height) {
                    throw invalidImage();
                }
                return new DecodedImage(toSafeColorModel(image, format), format);
            } finally {
                reader.dispose();
            }
        }
    }

    private static ImageFormat inspect(byte[] source) throws IOException {
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Formato immagine non riconosciuto");
            }

            ImageReader reader = readers.next();
            try {
                ImageFormat format = ImageFormat.from(reader.getFormatName());
                reader.setInput(input, true, true);
                validateDimensions(reader.getWidth(0), reader.getHeight(0));
                return format;
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage toSafeColorModel(BufferedImage source, ImageFormat format) {
        boolean keepAlpha = format == ImageFormat.PNG && source.getColorModel().hasAlpha();
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(),
                keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            if (!keepAlpha) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
            }
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
            source.flush();
        }
        return target;
    }

    private static PhotoData encode(DecodedImage decoded) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(decoded.format.formatName);
        if (!writers.hasNext()) {
            throw new IOException("Encoder immagine non disponibile");
        }

        ImageWriter writer = writers.next();
        LimitedByteArrayOutputStream bytes = new LimitedByteArrayOutputStream(Math.toIntExact(MAX_FILE_SIZE));
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (decoded.format == ImageFormat.JPEG && parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(0.88f);
            }
            writer.write(null, new IIOImage(decoded.image, null, null), parameters);
            output.flush();
        } finally {
            writer.dispose();
            decoded.image.flush();
        }

        byte[] sanitized = bytes.toByteArray();
        if (sanitized.length == 0 || sanitized.length > MAX_FILE_SIZE) {
            throw invalidImage();
        }
        return new PhotoData(sanitized, decoded.format.contentType);
    }

    private static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) {
            throw invalidImage();
        }
    }

    private static ResponseStatusException invalidImage() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_IMAGE_MESSAGE);
    }

    private record DecodedImage(BufferedImage image, ImageFormat format) {
    }

    private enum ImageFormat {
        JPEG("jpeg", "image/jpeg"),
        PNG("png", "image/png");

        private final String formatName;
        private final String contentType;

        ImageFormat(String formatName, String contentType) {
            this.formatName = formatName;
            this.contentType = contentType;
        }

        private static ImageFormat from(String formatName) {
            return switch (formatName.toLowerCase(Locale.ROOT)) {
                case "jpg", "jpeg" -> JPEG;
                case "png" -> PNG;
                default -> throw invalidImage();
            };
        }
    }

    private static final class LimitedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int maxSize;

        private LimitedByteArrayOutputStream(int maxSize) {
            this.maxSize = maxSize;
        }

        @Override
        public synchronized void write(int value) {
            ensureCapacityFor(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] buffer, int offset, int length) {
            ensureCapacityFor(length);
            super.write(buffer, offset, length);
        }

        private void ensureCapacityFor(int additionalBytes) {
            if (additionalBytes < 0 || count > maxSize - additionalBytes) {
                throw invalidImage();
            }
        }
    }
}
