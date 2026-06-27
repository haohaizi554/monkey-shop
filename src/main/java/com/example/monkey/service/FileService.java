package com.example.monkey.service;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final long MAX_UPLOAD_BYTES = 5L * 1024L * 1024L;

    private final long maxImagePixels;
    private final Path uploadRoot;
    private final MimeDetector mimeDetector;
    private final VirusScanner virusScanner;

    public FileService(
            @Value("${app.upload.max-image-pixels:12000000}") long maxImagePixels,
            @Value("${app.upload.path:uploads/images}") String uploadPath,
            @Value("${app.upload.virus-scan.enabled:false}") boolean virusScanEnabled,
            @Value("${app.upload.virus-scan.host:127.0.0.1}") String virusScanHost,
            @Value("${app.upload.virus-scan.port:3310}") int virusScanPort,
            @Value("${app.upload.virus-scan.timeout-millis:5000}") int virusScanTimeoutMillis) {
        this(
                maxImagePixels,
                uploadPath,
                new TikaMimeDetector(),
                virusScanEnabled
                        ? new ClamAvVirusScanner(virusScanHost, virusScanPort, virusScanTimeoutMillis)
                        : new NoOpVirusScanner());
    }

    FileService(long maxImagePixels, String uploadPath) {
        this(maxImagePixels, uploadPath, new TikaMimeDetector(), new NoOpVirusScanner());
    }

    FileService(long maxImagePixels, String uploadPath, MimeDetector mimeDetector) {
        this(maxImagePixels, uploadPath, mimeDetector, new NoOpVirusScanner());
    }

    FileService(long maxImagePixels, String uploadPath, MimeDetector mimeDetector, VirusScanner virusScanner) {
        this.maxImagePixels = maxImagePixels;
        this.uploadRoot = Path.of(uploadPath).toAbsolutePath().normalize();
        this.mimeDetector = mimeDetector;
        this.virusScanner = virusScanner;
    }

    public String uploadFile(MultipartFile file, String type) {
        if (file == null || file.isEmpty()) {
            return "error:empty file";
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return "error:file too large";
        }
        if (!"avatar".equals(type) && !"product".equals(type)) {
            return "error:unsupported upload type";
        }

        try {
            ImageFormat format = detectFormat(file);
            if (format == null) {
                return "error:unsupported image format";
            }
            String detectedMimeType = mimeDetector.detect(file);
            if (!format.mediaType().equalsIgnoreCase(detectedMimeType)) {
                return "error:unsupported image MIME type";
            }
            try {
                virusScanner.assertClean(file);
            } catch (MalwareDetectedException e) {
                log.warn("Rejected uploaded image after virus scan: {}", e.getMessage());
                return "error:malware detected";
            } catch (IOException e) {
                log.warn("Virus scan failed for upload type {}", type, e);
                return "error:virus scan unavailable";
            }

            ImageReadResult readResult = readImageSafely(file);
            BufferedImage finalImage = readResult.image();
            boolean isCropped = false;
            if ("product".equals(type) && readResult.width() != readResult.height()) {
                int size = Math.min(readResult.width(), readResult.height());
                int x = (readResult.width() - size) / 2;
                int y = (readResult.height() - size) / 2;
                finalImage = readResult.image().getSubimage(x, y, size, size);
                isCropped = true;
            }

            String subDir = "avatar".equals(type) ? "avatar" : "product";
            String newFileName = UUID.randomUUID() + "." + format.extension();
            Path uploadDir = uploadRoot.resolve(subDir).normalize();
            if (!uploadDir.startsWith(uploadRoot)) {
                return "error:invalid upload path";
            }
            Files.createDirectories(uploadDir);
            Path dest = uploadDir.resolve(newFileName).normalize();
            if (!dest.startsWith(uploadDir)) {
                return "error:invalid upload path";
            }

            if (!ImageIO.write(finalImage, format.imageIoName(), dest.toFile())) {
                return "error:image encoding failed";
            }

            return (isCropped ? "cropped:" : "ok:") + "/images/" + subDir + "/" + newFileName;
        } catch (IllegalArgumentException e) {
            return "error:" + e.getMessage();
        } catch (IOException e) {
            log.warn("Image upload failed for type {}", type, e);
            return "error:upload failed";
        }
    }

    private ImageFormat detectFormat(MultipartFile file) throws IOException {
        byte[] header = readHeader(file);
        if (header.length >= 8
                && header[0] == (byte) 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47
                && header[4] == 0x0D
                && header[5] == 0x0A
                && header[6] == 0x1A
                && header[7] == 0x0A) {
            return ImageFormat.PNG;
        }
        if (header.length >= 3
                && header[0] == (byte) 0xFF
                && header[1] == (byte) 0xD8
                && header[2] == (byte) 0xFF) {
            return ImageFormat.JPEG;
        }
        return null;
    }

    private byte[] readHeader(MultipartFile file) throws IOException {
        byte[] header = new byte[12];
        try (InputStream input = file.getInputStream()) {
            int read = input.read(header);
            return read <= 0 ? new byte[0] : Arrays.copyOf(header, read);
        }
    }

    private ImageReadResult readImageSafely(MultipartFile file) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(file.getInputStream())) {
            if (imageInput == null) {
                throw new IllegalArgumentException("unsupported image format");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("unsupported image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * (long) height;
                if (width <= 0 || height <= 0 || pixels > maxImagePixels) {
                    throw new IllegalArgumentException("image dimensions are not allowed");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException("unsupported image format");
                }
                return new ImageReadResult(image, width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private enum ImageFormat {
        JPEG("jpg", "jpg", "image/jpeg"),
        PNG("png", "png", "image/png");

        private final String extension;
        private final String imageIoName;
        private final String mediaType;

        ImageFormat(String extension, String imageIoName, String mediaType) {
            this.extension = extension.toLowerCase(Locale.ROOT);
            this.imageIoName = imageIoName;
            this.mediaType = mediaType;
        }

        String extension() {
            return extension;
        }

        String imageIoName() {
            return imageIoName;
        }

        String mediaType() {
            return mediaType;
        }
    }

    @FunctionalInterface
    interface MimeDetector {
        String detect(MultipartFile file) throws IOException;
    }

    private static final class TikaMimeDetector implements MimeDetector {

        private final Tika tika = new Tika();

        @Override
        public String detect(MultipartFile file) throws IOException {
            try (InputStream input = file.getInputStream()) {
                return tika.detect(input);
            }
        }
    }

    private record ImageReadResult(BufferedImage image, int width, int height) {}
}
