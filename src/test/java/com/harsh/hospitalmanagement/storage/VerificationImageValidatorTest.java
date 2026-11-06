package com.harsh.hospitalmanagement.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class VerificationImageValidatorTest {

    private final VerificationImageValidator validator = new VerificationImageValidator();

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void reEncodesValidSyntheticImages(String extension) throws IOException {
        var image = validate("image/" + extension, imageBytes(extension));
        assertThat(image.contentType()).isEqualTo("image/" + extension);
        assertThat(image.size()).isPositive().isLessThan(VerificationImageValidator.MAX_FILE_BYTES);
        var decoded = ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(image.encoded())));
        assertThat(decoded.getWidth()).isEqualTo(8);
        assertThat(decoded.getHeight()).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png"})
    void acceptsProgressiveJpegAndInterlacedPng(String extension) throws IOException {
        var output = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName(extension).next();
        try (var stream = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(stream);
            var params = writer.getDefaultWriteParam();
            params.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new IIOImage(new BufferedImage(9, 7, BufferedImage.TYPE_INT_RGB), null, null), params);
        } finally {
            writer.dispose();
        }
        assertThat(validate("image/" + extension, output.toByteArray()).size()).isPositive();
    }

    @Test
    void jpegReencodingUsesExplicitQualityAndPreservesSmallTextContrast() throws IOException {
        var text = new BufferedImage(180, 24, BufferedImage.TYPE_INT_RGB);
        var graphics = text.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, text.getWidth(), text.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.drawString("HMS SYNTHETIC ID 0123456789", 4, 15);
        } finally {
            graphics.dispose();
        }

        byte[] source = jpegAtQuality(text, 1.0f);
        var reencoded = validate("image/jpeg", source);
        var inputPixels = ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(source)));
        var reference = new BufferedImage(text.getWidth(), text.getHeight(), BufferedImage.TYPE_INT_RGB);
        reference.setRGB(0, 0, text.getWidth(), text.getHeight(),
                inputPixels.getRGB(0, 0, text.getWidth(), text.getHeight(), null, 0, text.getWidth()), 0, text.getWidth());
        assertThat(reencoded.encoded()).isEqualTo(jpegAtQuality(reference, 0.92f));

        var result = ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(reencoded.encoded())));
        int foreground = 0;
        int visibleForeground = 0;
        int background = 0;
        int cleanBackground = 0;
        // Measure dark strokes and the light spaces around them independently.
        for (int y = 5; y <= 16; y++) {
            for (int x = 4; x < 160; x++) {
                int brightness = result.getRGB(x, y) & 0xff;
                if (text.getRGB(x, y) == Color.BLACK.getRGB()) {
                    foreground++;
                    if (brightness <= 64) {
                        visibleForeground++;
                    }
                } else {
                    background++;
                    if (brightness >= 192) {
                        cleanBackground++;
                    }
                }
            }
        }
        assertThat(foreground).isGreaterThan(100);
        assertThat((double) visibleForeground / foreground).isGreaterThanOrEqualTo(0.98);
        assertThat((double) cleanBackground / background).isGreaterThanOrEqualTo(0.98);
    }

    @Test
    void admitsThreeWaitingValidationsAndRejectsOverflow() throws Exception {
        byte[] png = imageBytes("png");
        var waiters = new ArrayList<ValidationTask>();
        try (var active = new HeldValidation(png)) {
            for (int i = 0; i < 3; i++) {
                var waiter = startValidation(() -> validate("image/png", png));
                waiters.add(waiter);
                awaitDecoderWait(waiter.thread());
            }
            assertThatThrownBy(() -> validate("image/png", png))
                    .isInstanceOf(IOException.class).hasMessageContaining("queue is full");
        }
        for (var waiter : waiters) {
            assertThat(waiter.result().get(5, SECONDS).contentType()).isEqualTo("image/png");
        }
    }

    @Test
    void timesOutWhenTheActiveValidationDoesNotReleaseTheDecoder() throws Exception {
        byte[] png = imageBytes("png");
        try (var active = new HeldValidation(png)) {
            var waiter = startValidation(() -> validate("image/png", png));
            assertThatThrownBy(() -> waiter.result().get(10, SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IOException.class)
                    .hasRootCauseMessage("Timed out waiting for verification image validation");
        }
        assertThat(validate("image/png", png).size()).isPositive();
    }

    @Test
    void restoresInterruptStatusWhenWaitingForDecoder() throws Exception {
        byte[] png = imageBytes("png");
        var interrupted = new AtomicBoolean();
        try (var active = new HeldValidation(png)) {
            var waiter = startValidation(() -> {
                try {
                    return validate("image/png", png);
                } catch (IOException failure) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                    throw failure;
                }
            });
            awaitDecoderWait(waiter.thread());
            waiter.thread().interrupt();
            assertThatThrownBy(() -> waiter.result().get(5, SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IOException.class)
                    .hasRootCauseInstanceOf(InterruptedException.class);
            assertThat(interrupted).isTrue();
        }
        assertThat(validate("image/png", png).size()).isPositive();
    }

    @Test
    void rejectsSpoofedMimeAndUnsupportedType() throws IOException {
        byte[] png = imageBytes("png");
        assertThatThrownBy(() -> validate("image/jpeg", png)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validate("application/pdf", png)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validate("image/png", new byte[] {1, 2, 3})).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsTruncationAndTrailingPayloads() throws IOException {
        byte[] jpeg = imageBytes("jpeg");
        byte[] png = imageBytes("png");
        byte[] jpegWithTrailer = Arrays.copyOf(jpeg, jpeg.length + 3);
        jpegWithTrailer[jpegWithTrailer.length - 2] = (byte) 0xff;
        jpegWithTrailer[jpegWithTrailer.length - 1] = (byte) 0xd9;
        assertThatThrownBy(() -> validate("image/jpeg", jpegWithTrailer)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validate("image/jpeg", Arrays.copyOf(jpeg, jpeg.length - 2))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validate("image/png", Arrays.copyOf(png, png.length - 1))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validate("image/png", Arrays.copyOf(png, png.length + 1))).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsPngChunkCrcCorruption() throws IOException {
        byte[] corrupt = imageBytes("png");
        corrupt[corrupt.length - 1] ^= 1;
        assertThatThrownBy(() -> validate("image/png", corrupt)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsCorruptImageDataEvenWithValidPngChunkCrc() throws IOException {
        byte[] corrupt = imageBytes("png");
        int data = pngChunk(corrupt, 0x49444154);
        corrupt[data + 8] ^= (byte) 0xff;
        updateCrc(corrupt, data);
        assertThatThrownBy(() -> validate("image/png", corrupt)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsAnInvalidZlibChecksumEvenWithValidPngChunkCrc() throws IOException {
        byte[] corrupt = imageBytes("png");
        int data = pngChunk(corrupt, 0x49444154);
        corrupt[data + 8 + ByteBuffer.wrap(corrupt).getInt(data) - 1] ^= 1;
        updateCrc(corrupt, data);
        assertThatThrownBy(() -> validate("image/png", corrupt)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsBytesAfterThePngDeflateStreamEvenWithValidChunkCrc() throws IOException {
        byte[] png = imageBytes("png");
        int data = pngChunk(png, 0x49444154);
        int length = ByteBuffer.wrap(png).getInt(data);
        byte[] corrupt = insert(png, data + 8 + length, new byte[] {42});
        ByteBuffer.wrap(corrupt).putInt(data, length + 1);
        updateCrc(corrupt, data);
        assertThatThrownBy(() -> validate("image/png", corrupt)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsOversizedFilesAndRawRequestBodies() {
        assertThatThrownBy(() -> validate("image/png", new byte[VerificationImageValidator.MAX_FILE_BYTES + 1]))
                .isInstanceOf(IOException.class).hasMessageContaining("5 MiB");
        assertThatThrownBy(() -> validator.readBoundedRequest(new ByteArrayInputStream(
                new byte[VerificationImageValidator.MAX_REQUEST_BYTES + 1])))
                .isInstanceOf(IOException.class).hasMessageContaining("11 MiB");
    }

    @Test
    void rejectsExcessiveDimensionsBeforeDecoding() throws IOException {
        byte[] image = imageBytes("png");
        ByteBuffer.wrap(image).putInt(16, 4_001).putInt(20, 3_000);
        updateCrc(image, 8);
        assertThatThrownBy(() -> validate("image/png", image))
                .isInstanceOf(IOException.class).hasMessageContaining("12 megapixel");
    }

    @Test
    void reEncodingRemovesTextAndJpegAppMetadata() throws IOException {
        byte[] marker = "synthetic-sensitive-metadata".getBytes(StandardCharsets.US_ASCII);
        var pngText = ByteBuffer.allocate(12 + 5 + marker.length)
                .putInt(5 + marker.length).putInt(0x74455874).put(new byte[] {'N', 'o', 't', 'e', 0}).put(marker).array();
        updateCrc(pngText, 0);
        byte[] png = insert(imageBytes("png"), 33, pngText);
        var jpegApp = ByteBuffer.allocate(4 + marker.length)
                .put((byte) 0xff).put((byte) 0xe1).putShort((short) (marker.length + 2)).put(marker).array();
        byte[] jpeg = insert(imageBytes("jpeg"), 2, jpegApp);
        assertThat(new String(validate("image/png", png).encoded(), StandardCharsets.ISO_8859_1))
                .doesNotContain("synthetic-sensitive-metadata", "tEXt");
        assertThat(new String(validate("image/jpeg", jpeg).encoded(), StandardCharsets.ISO_8859_1))
                .doesNotContain("synthetic-sensitive-metadata");
    }

    static byte[] imageBytes(String format) throws IOException {
        var image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 3, Color.BLUE.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            assertThat(ImageIO.write(image, format, stream)).isTrue();
        }
        return bytes.toByteArray();
    }

    private static byte[] jpegAtQuality(BufferedImage image, float quality) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static ValidationTask startValidation(Callable<VerificationImageValidator.ValidatedImage> validation) {
        var result = new FutureTask<>(validation);
        var thread = Thread.ofPlatform().daemon().start(result);
        return new ValidationTask(thread, result);
    }

    private static void awaitDecoderWait(Thread waiter) {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (waiter.isAlive() && waiter.getState() != Thread.State.TIMED_WAITING) {
                Thread.sleep(1);
            }
            assertThat(waiter.getState()).isEqualTo(Thread.State.TIMED_WAITING);
        });
    }

    private record ValidationTask(Thread thread, FutureTask<VerificationImageValidator.ValidatedImage> result) {
    }

    private final class HeldValidation implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final ValidationTask active;

        HeldValidation(byte[] png) throws InterruptedException {
            var entered = new CountDownLatch(1);
            var input = new FilterInputStream(new ByteArrayInputStream(png)) {
                @Override
                public byte[] readNBytes(int limit) throws IOException {
                    entered.countDown();
                    try {
                        if (!release.await(20, SECONDS)) {
                            throw new IOException("Test did not release the active validation");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Active test validation interrupted", failure);
                    }
                    return super.readNBytes(limit);
                }
            };
            active = startValidation(() -> validator.validate("image/png", input));
            try {
                assertThat(entered.await(5, SECONDS)).isTrue();
            } catch (InterruptedException | AssertionError failure) {
                release.countDown();
                throw failure;
            }
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            active.result().get(5, SECONDS);
        }
    }

    private VerificationImageValidator.ValidatedImage validate(String mime, byte[] bytes) throws IOException {
        return validator.validate(mime, new ByteArrayInputStream(bytes));
    }

    private static byte[] insert(byte[] source, int position, byte[] insert) {
        return ByteBuffer.allocate(source.length + insert.length)
                .put(source, 0, position).put(insert).put(source, position, source.length - position).array();
    }

    private static int pngChunk(byte[] png, int kind) {
        var data = ByteBuffer.wrap(png);
        for (int start = 8; start < png.length; start += 12 + data.getInt(start)) {
            if (data.getInt(start + 4) == kind) {
                return start;
            }
        }
        throw new AssertionError("Synthetic PNG is missing the expected chunk");
    }

    private static void updateCrc(byte[] png, int start) {
        var data = ByteBuffer.wrap(png);
        int length = data.getInt(start);
        var crc = new CRC32();
        crc.update(png, start + 4, length + 4);
        data.putInt(start + 8 + length, (int) crc.getValue());
    }
}
