package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class FileServiceTest {

    @TempDir
    Path uploadRoot;

    @Test
    void storesAvatarUnderConfiguredUploadRoot() throws IOException {
        FileService fileService = new FileService(12_000_000L, uploadRoot.toString());
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        String result = fileService.uploadFile(file, "avatar");

        assertThat(result).startsWith("ok:/images/avatar/").endsWith(".png");
        Path storedFile = uploadRoot.resolve(result.substring("ok:/images/".length()));
        assertThat(storedFile).isRegularFile();
    }

    @Test
    void storesImageUsingDetectedContentInsteadOfClientMetadata() throws IOException {
        FileService fileService = new FileService(12_000_000L, uploadRoot.toString());
        byte[] pngBytes = imageBytes("png", 2, 2);
        MockMultipartFile file = new MockMultipartFile("avatar", "avatar.jpg", "text/plain", pngBytes);

        String result = fileService.uploadFile(file, "avatar");

        assertThat(result).startsWith("ok:/images/avatar/").endsWith(".png");
        Path storedFile = uploadRoot.resolve(result.substring("ok:/images/".length()));
        assertThat(storedFile).isRegularFile();
    }

    @Test
    void rejectsWhenMagicNumberAndMimeDetectorDisagree() throws IOException {
        FileService fileService = new FileService(12_000_000L, uploadRoot.toString(), file -> "application/pdf");
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        String result = fileService.uploadFile(file, "avatar");

        assertThat(result).isEqualTo("error:unsupported image MIME type");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void rejectsWhenVirusScannerReportsMalware() throws IOException {
        FileService fileService = new FileService(
                12_000_000L,
                uploadRoot.toString(),
                file -> "image/png",
                file -> {
                    throw new MalwareDetectedException("stream: Eicar-Test-Signature FOUND");
                });
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        String result = fileService.uploadFile(file, "avatar");

        assertThat(result).isEqualTo("error:malware detected");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void failsClosedWhenVirusScannerIsUnavailable() throws IOException {
        FileService fileService = new FileService(
                12_000_000L,
                uploadRoot.toString(),
                file -> "image/png",
                file -> {
                    throw new IOException("clamd unavailable");
                });
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        String result = fileService.uploadFile(file, "avatar");

        assertThat(result).isEqualTo("error:virus scan unavailable");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void rejectsUnsupportedUploadTypeBeforeWriting() {
        FileService fileService = new FileService(12_000_000L, uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile("file", "x.txt", "text/plain", "not-an-image".getBytes());

        String result = fileService.uploadFile(file, "document");

        assertThat(result).isEqualTo("error:unsupported upload type");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void rejectsImageDimensionsOverConfiguredPixelLimit() throws IOException {
        FileService fileService = new FileService(3L, uploadRoot.toString());
        MockMultipartFile file = imageFile("avatar", "avatar.png", "png", 2, 2);

        String result = fileService.uploadFile(file, "avatar");

        assertThat(result).isEqualTo("error:image dimensions are not allowed");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void cropsNonSquareProductImages() throws IOException {
        FileService fileService = new FileService(12_000_000L, uploadRoot.toString());
        MockMultipartFile file = imageFile("product", "product.png", "png", 3, 2);

        String result = fileService.uploadFile(file, "product");

        assertThat(result).startsWith("cropped:/images/product/").endsWith(".png");
        Path storedFile = uploadRoot.resolve(result.substring("cropped:/images/".length()));
        BufferedImage storedImage = ImageIO.read(storedFile.toFile());
        assertThat(storedImage.getWidth()).isEqualTo(2);
        assertThat(storedImage.getHeight()).isEqualTo(2);
    }

    @Test
    void rejectsFilesWithoutAllowedMagicNumber() {
        FileService fileService = new FileService(12_000_000L, uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "not-a-png".getBytes());

        String result = fileService.uploadFile(file, "avatar");

        assertThat(result).isEqualTo("error:unsupported image format");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    private static MockMultipartFile imageFile(String fieldName, String originalName, String format, int width, int height)
            throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, Color.BLUE.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return new MockMultipartFile(fieldName, originalName, "image/" + format, output.toByteArray());
    }

    private static byte[] imageBytes(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, Color.BLUE.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }
}
