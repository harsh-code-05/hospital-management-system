package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.entity.DoctorVerificationDocumentPolicy;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class VerificationImageValidator {

    public static final int MAX_FILE_BYTES = DoctorVerificationDocumentPolicy.MAX_FILE_SIZE_BYTES;
    public static final int MAX_REQUEST_BYTES = 11 * 1024 * 1024;
    public static final long MAX_PIXELS = 12_000_000;

    private static final int MAX_ADMITTED_VALIDATIONS = 4;
    private static final long VALIDATION_WAIT_SECONDS = 5;
    private static final float JPEG_QUALITY = 0.92f;
    private static final Semaphore ADMISSION = new Semaphore(MAX_ADMITTED_VALIDATIONS);
    private static final Semaphore DECODER = new Semaphore(1, true);
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final int IHDR = 0x49484452;
    private static final int PLTE = 0x504c5445;
    private static final int TRNS = 0x74524e53;
    private static final int IDAT = 0x49444154;
    private static final int IEND = 0x49454e44;
    private static final int ACTL = 0x6163544c;
    private static final int FCTL = 0x6663544c;
    private static final int FDAT = 0x66644154;
    private static final int[][] ADAM7 = {
            {0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4},
            {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}
    };

    /** Bounds the actual raw body, including any envelope, before a future upload endpoint parses it. */
    public byte[] readBoundedRequest(InputStream body) throws IOException {
        return readBounded(body, MAX_REQUEST_BYTES, "Verification upload exceeds 11 MiB");
    }

    public ValidatedImage validate(String contentType, InputStream input) throws IOException {
        Format format = Format.fromMime(contentType);
        if (!ADMISSION.tryAcquire()) {
            throw new IOException("Verification image validation queue is full");
        }
        try {
            acquireDecoder();
            try {
                byte[] bytes = readBounded(input, MAX_FILE_BYTES, "Verification image exceeds 5 MiB");
                byte[] sanitized = switch (format) {
                    case PNG -> stripPngMetadata(bytes);
                    case JPEG -> stripJpegMetadata(bytes);
                };
                return new ValidatedImage(format, encode(format, decode(format, sanitized)));
            } finally {
                DECODER.release();
            }
        } finally {
            ADMISSION.release();
        }
    }

    private static void acquireDecoder() throws IOException {
        try {
            if (!DECODER.tryAcquire(VALIDATION_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for verification image validation");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for verification image validation", exception);
        }
    }

    private static byte[] readBounded(InputStream input, int limit, String error) throws IOException {
        byte[] bytes = Objects.requireNonNull(input).readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new IOException(error);
        }
        return bytes;
    }

    private static BufferedImage decode(Format format, byte[] bytes) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName(format.extension).next();
        try (var source = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(source, true, true);
            boolean[] warned = {false};
            reader.addIIOReadWarningListener((ignored, message) -> warned[0] = true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            requireDimensions(width, height);
            BufferedImage image = reader.read(0);
            if (warned[0] || image == null || image.getWidth() != width || image.getHeight() != height) {
                throw invalidImage();
            }
            return image;
        } catch (RuntimeException exception) {
            throw invalidImage();
        } finally {
            reader.dispose();
        }
    }

    private static void requireDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw new IOException("Verification image dimensions exceed the 12 megapixel limit or are invalid");
        }
    }

    private static byte[] encode(Format format, BufferedImage image) throws IOException {
        int type = format == Format.PNG && image.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        var canonical = new BufferedImage(image.getWidth(), image.getHeight(), type);
        var graphics = canonical.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
            image.flush();
        }

        var writer = ImageIO.getImageWritersByFormatName(format.extension).next();
        var output = new ByteArrayOutputStream();
        try (var destination = new BoundedImageOutput(output)) {
            writer.setOutput(destination);
            var params = writer.getDefaultWriteParam();
            if (format == Format.JPEG) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(canonical, null, null), params);
        } finally {
            writer.dispose();
            canonical.flush();
        }
        return output.toByteArray();
    }

    // The JDK readers may ignore trailers and metadata corruption. Check framing before decoding.
    private static byte[] stripPngMetadata(byte[] bytes) throws IOException {
        if (bytes.length < PNG_SIGNATURE.length || !Arrays.equals(
                bytes, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            throw invalidImage();
        }
        var input = ByteBuffer.wrap(bytes).position(PNG_SIGNATURE.length);
        var clean = new ByteArrayOutputStream(bytes.length);
        clean.writeBytes(PNG_SIGNATURE);
        var crc = new CRC32();
        int previous = 0;
        boolean seenImageData = false;
        try (var raster = new PngData()) {
            while (input.remaining() >= 12) {
                int start = input.position();
                int length = input.getInt();
                int chunk = input.getInt();
                int payload = input.position();
                if (length < 0 || length > input.remaining() - 4) {
                    throw invalidImage();
                }
                crc.reset();
                crc.update(bytes, start + 4, length + 4);
                input.position(payload + length);
                if (input.getInt() != (int) crc.getValue() || (previous == 0) != (chunk == IHDR)) {
                    throw invalidImage();
                }
                switch (chunk) {
                    case IHDR -> {
                        if (length != 13) {
                            throw invalidImage();
                        }
                        raster.expected = expectedPngBytes(input, payload);
                    }
                    case IDAT -> {
                        if (seenImageData && previous != IDAT) {
                            throw invalidImage();
                        }
                        raster.consume(bytes, payload, length);
                        seenImageData = true;
                    }
                    case IEND -> {
                        if (!seenImageData || length != 0 || input.hasRemaining()) {
                            throw invalidImage();
                        }
                        raster.requireComplete();
                        clean.write(bytes, start, input.position() - start);
                        return clean.toByteArray();
                    }
                    case PLTE, TRNS -> {
                        if (seenImageData) {
                            throw invalidImage();
                        }
                    }
                    default -> {
                        if ((chunk & 0x20000000) == 0 || chunk == ACTL || chunk == FCTL || chunk == FDAT) {
                            throw invalidImage();
                        }
                        previous = chunk;
                        continue;
                    }
                }
                clean.write(bytes, start, input.position() - start);
                previous = chunk;
            }
        }
        throw invalidImage();
    }

    private static long expectedPngBytes(ByteBuffer input, int offset) throws IOException {
        int width = input.getInt(offset);
        int height = input.getInt(offset + 4);
        requireDimensions(width, height);
        int depth = input.get(offset + 8);
        int color = input.get(offset + 9);
        int interlace = input.get(offset + 12);
        int channels = switch (color) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> throw invalidImage();
        };
        boolean validDepth = depth == 8 || (depth == 16 && color != 3)
                || ((color == 0 || color == 3) && (depth == 1 || depth == 2 || depth == 4));
        if (!validDepth || input.get(offset + 10) != 0 || input.get(offset + 11) != 0
                || (interlace != 0 && interlace != 1)) {
            throw invalidImage();
        }
        int bitsPerPixel = depth * channels;
        if (interlace == 0) {
            return scanlineBytes(width, height, bitsPerPixel);
        }
        long size = 0;
        for (int[] pass : ADAM7) {
            int columns = (width - pass[0] + pass[2] - 1) / pass[2];
            int rows = (height - pass[1] + pass[3] - 1) / pass[3];
            if (columns > 0 && rows > 0) {
                size += scanlineBytes(columns, rows, bitsPerPixel);
            }
        }
        return size;
    }

    private static long scanlineBytes(int columns, int rows, int bitsPerPixel) {
        return rows * (1 + (columns * (long) bitsPerPixel + 7) / 8);
    }

    private static byte[] stripJpegMetadata(byte[] bytes) throws IOException {
        if (bytes.length < 4 || unsigned(bytes, 0) != 0xff || unsigned(bytes, 1) != 0xd8) {
            throw invalidImage();
        }
        var clean = new ByteArrayOutputStream(bytes.length);
        clean.write(bytes, 0, 2);
        int position = 2;
        boolean scan = false;
        boolean seenScan = false;
        while (position < bytes.length) {
            if (scan) {
                int start = position;
                position = nextJpegMarker(bytes, position);
                clean.write(bytes, start, position - start);
            }
            if (unsigned(bytes, position) != 0xff) {
                throw invalidImage();
            }
            do {
                position++;
            } while (position < bytes.length && unsigned(bytes, position) == 0xff);
            if (position == bytes.length) {
                throw invalidImage();
            }
            int marker = unsigned(bytes, position++);
            if (marker == 0xd9 && seenScan && position == bytes.length) {
                clean.write(0xff);
                clean.write(marker);
                return clean.toByteArray();
            }
            if (marker <= 1 || (marker >= 0xd0 && marker <= 0xd9) || position > bytes.length - 2) {
                throw invalidImage();
            }
            int length = unsigned(bytes, position) * 256 + unsigned(bytes, position + 1);
            if (length < 2 || length > bytes.length - position) {
                throw invalidImage();
            }
            // Keep JFIF and Adobe color markers. Other APP segments and comments carry no raster data.
            if (marker != 0xfe && (marker <= 0xe0 || marker == 0xee || marker > 0xef)) {
                clean.write(0xff);
                clean.write(marker);
                clean.write(bytes, position, length);
            }
            position += length;
            scan = marker == 0xda;
            seenScan |= scan;
        }
        throw invalidImage();
    }

    private static int nextJpegMarker(byte[] bytes, int position) throws IOException {
        while (position < bytes.length - 1) {
            int start = position++;
            if (unsigned(bytes, start) != 0xff) {
                continue;
            }
            while (position < bytes.length && unsigned(bytes, position) == 0xff) {
                position++;
            }
            if (position == bytes.length) {
                break;
            }
            int next = unsigned(bytes, position++);
            if (next != 0 && (next < 0xd0 || next > 0xd7)) {
                return start;
            }
        }
        throw invalidImage();
    }

    private static int unsigned(byte[] bytes, int index) {
        return Byte.toUnsignedInt(bytes[index]);
    }

    private static IOException invalidImage() {
        return new IOException("Invalid or corrupt JPEG or PNG verification image");
    }

    enum Format {
        JPEG((byte) 1, "image/jpeg", "jpeg"), PNG((byte) 2, "image/png", "png");

        final byte code;
        final String mime;
        final String extension;

        Format(byte code, String mime, String extension) {
            this.code = code;
            this.mime = mime;
            this.extension = extension;
        }

        static Format fromMime(String mime) throws IOException {
            if (mime != null) {
                String normalized = mime.strip().toLowerCase(Locale.ROOT);
                for (Format format : values()) {
                    if (format.mime.equals(normalized)) {
                        return format;
                    }
                }
            }
            throw new IOException("Only JPEG and PNG verification images are supported");
        }

        static Format fromCode(byte code) {
            return switch (code) {
                case 1 -> JPEG;
                case 2 -> PNG;
                default -> null;
            };
        }
    }

    public static final class ValidatedImage {
        private final Format format;
        private final byte[] bytes;

        private ValidatedImage(Format format, byte[] bytes) {
            this.format = format;
            this.bytes = bytes;
        }

        Format format() {
            return format;
        }

        byte[] encoded() {
            return bytes;
        }

        public int size() {
            return bytes.length;
        }

        public String contentType() {
            return format.mime;
        }
    }

    /** Verifies that IDAT contains exactly one bounded zlib stream with no leftover bytes. */
    private static final class PngData implements AutoCloseable {
        private final Inflater inflater = new Inflater();
        private final byte[] work = new byte[8192];
        private long expected;
        private long decoded;

        void consume(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return;
            }
            if (inflater.finished()) {
                throw invalidImage();
            }
            inflater.setInput(bytes, offset, length);
            try {
                while (!inflater.finished()) {
                    int count = inflater.inflate(work);
                    decoded += count;
                    if (decoded > expected || inflater.needsDictionary()) {
                        throw invalidImage();
                    }
                    if (count == 0 && !inflater.finished()) {
                        if (inflater.needsInput()) {
                            break;
                        }
                        throw invalidImage();
                    }
                }
            } catch (DataFormatException exception) {
                throw invalidImage();
            }
            if (inflater.finished() && inflater.getRemaining() != 0) {
                throw invalidImage();
            }
        }

        void requireComplete() throws IOException {
            if (!inflater.finished() || decoded != expected) {
                throw invalidImage();
            }
        }

        @Override
        public void close() {
            inflater.end();
        }
    }

    private static final class BoundedImageOutput extends MemoryCacheImageOutputStream {
        BoundedImageOutput(ByteArrayOutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            requireRoom(1);
            super.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireRoom(length);
            super.write(bytes, offset, length);
        }

        private void requireRoom(int length) throws IOException {
            if (getStreamPosition() > MAX_FILE_BYTES - length) {
                throw new IOException("Re-encoded verification image exceeds 5 MiB");
            }
        }
    }
}
