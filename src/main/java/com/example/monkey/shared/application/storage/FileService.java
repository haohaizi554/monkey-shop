package com.example.monkey.shared.application.storage;

import com.example.monkey.shared.application.storage.dto.PresignedGetUrlResponseDto;
import com.example.monkey.shared.application.storage.dto.PresignedUploadResponseDto;
import com.example.monkey.shared.application.storage.dto.UploadResponseDto;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.storage.MalwareDetectedException;
import com.example.monkey.shared.domain.storage.ObjectStorageKey;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import com.example.monkey.shared.domain.storage.VirusScanner;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final long MAX_UPLOAD_BYTES = 5L * 1024L * 1024L;

    private final long maxImagePixels;
    private final MimeDetector mimeDetector;
    private final VirusScanner virusScanner;
    private final ObjectStorageService objectStorageService;
    private final ImageVariantService imageVariantService;
    private final Duration presignedGetTtl;
    private final Duration presignedPostTtl;

    public FileService(
            @Value("${app.upload.max-image-pixels:12000000}") long maxImagePixels,
            @Value("${app.storage.presigned-get-ttl:PT15M}") Duration presignedGetTtl,
            @Value("${app.storage.presigned-post-ttl:PT15M}") Duration presignedPostTtl,
            VirusScanner virusScanner,
            ObjectStorageService objectStorageService,
            ImageVariantService imageVariantService) {
        this(
                maxImagePixels,
                objectStorageService,
                imageVariantService,
                new TikaMimeDetector(),
                virusScanner,
                presignedGetTtl,
                presignedPostTtl);
    }

    FileService(long maxImagePixels, ObjectStorageService objectStorageService) {
        this(maxImagePixels, objectStorageService, new TikaMimeDetector(), content -> {});
    }

    FileService(long maxImagePixels, ObjectStorageService objectStorageService, MimeDetector mimeDetector) {
        this(maxImagePixels, objectStorageService, mimeDetector, content -> {});
    }

    FileService(
            long maxImagePixels,
            ObjectStorageService objectStorageService,
            MimeDetector mimeDetector,
            VirusScanner virusScanner) {
        this(
                maxImagePixels,
                objectStorageService,
                ImageVariantService.disabled(objectStorageService),
                mimeDetector,
                virusScanner,
                Duration.ofMinutes(15),
                Duration.ofMinutes(15));
    }

    FileService(
            long maxImagePixels,
            ObjectStorageService objectStorageService,
            ImageVariantService imageVariantService,
            MimeDetector mimeDetector,
            VirusScanner virusScanner) {
        this(
                maxImagePixels,
                objectStorageService,
                imageVariantService,
                mimeDetector,
                virusScanner,
                Duration.ofMinutes(15),
                Duration.ofMinutes(15));
    }

    private FileService(
            long maxImagePixels,
            ObjectStorageService objectStorageService,
            ImageVariantService imageVariantService,
            MimeDetector mimeDetector,
            VirusScanner virusScanner,
            Duration presignedGetTtl,
            Duration presignedPostTtl) {
        this.maxImagePixels = maxImagePixels;
        this.mimeDetector = mimeDetector;
        this.virusScanner = virusScanner;
        this.objectStorageService = objectStorageService;
        this.imageVariantService = imageVariantService;
        this.presignedGetTtl = presignedGetTtl;
        this.presignedPostTtl = presignedPostTtl;
    }

    public UploadResponseDto uploadFile(UploadFileContent file, String type) {
        if (file == null || file.isEmpty()) {
            throw uploadFailure("empty file");
        }
        if (file.size() > MAX_UPLOAD_BYTES) {
            throw uploadFailure("file too large");
        }
        if (!"avatar".equals(type) && !"product".equals(type)) {
            throw uploadFailure("unsupported upload type");
        }

        try {
            ImageFormat format = detectFormat(file);
            if (format == null) {
                throw uploadFailure("unsupported image format");
            }
            String detectedMimeType = mimeDetector.detect(file);
            if (!format.mediaType().equalsIgnoreCase(detectedMimeType)) {
                throw uploadFailure("unsupported image MIME type");
            }
            try {
                virusScanner.assertClean(file.openStream());
            } catch (MalwareDetectedException e) {
                log.warn("Rejected uploaded image after virus scan: {}", e.getMessage());
                throw uploadFailure("malware detected");
            } catch (IOException e) {
                log.warn("Virus scan failed for upload type {}", type, e);
                throw uploadFailure(ErrorCode.INTERNAL_ERROR, "virus scan unavailable");
            }

            ImageReadResult readResult = readImageSafely(file);
            BufferedImage finalImage = readResult.image();
            boolean isCropped = false;
            if ("product".equals(type) && readResult.width() != readResult.height()) {
                finalImage = cropToSquare(readResult);
                isCropped = true;
            }

            String subDir = "avatar".equals(type) ? "avatar" : "product";
            String newFileName = UUID.randomUUID() + "." + format.extension();
            String objectKey = subDir + "/" + newFileName;

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(finalImage, format.imageIoName(), output)) {
                throw uploadFailure(ErrorCode.INTERNAL_ERROR, "image encoding failed");
            }

            ObjectStorageService.StoredObject storedObject =
                    objectStorageService.store(objectKey, output.toByteArray(), format.mediaType());
            Map<String, String> variants = imageVariantService.createVariants(finalImage, objectKey);
            return UploadDtoAssembler.uploadResponse(storedObject.publicUrl(), isCropped, variants);
        } catch (IllegalArgumentException e) {
            throw uploadFailure(e.getMessage());
        } catch (IOException e) {
            log.warn("Image upload failed for type {}", type, e);
            throw uploadFailure(ErrorCode.INTERNAL_ERROR, "upload failed");
        }
    }

    public PresignedUploadResponseDto createPresignedUpload(String type, String contentType) {
        if (!"avatar".equals(type) && !"product".equals(type)) {
            throw uploadFailure("unsupported upload type");
        }
        ImageFormat format = ImageFormat.fromMediaType(contentType);
        if (format == null) {
            throw uploadFailure("unsupported image MIME type");
        }
        String subDir = "avatar".equals(type) ? "avatar" : "product";
        String objectKey = subDir + "/" + UUID.randomUUID() + "." + format.extension();
        try {
            return UploadDtoAssembler.presignedUploadResponse(objectStorageService.createPresignedPost(
                    objectKey, format.mediaType(), MAX_UPLOAD_BYTES, presignedPostTtl));
        } catch (IOException e) {
            log.warn("Presigned upload creation failed for type {}", type, e);
            throw uploadFailure(ErrorCode.INTERNAL_ERROR, "presigned upload failed");
        }
    }

    public PresignedGetUrlResponseDto createPresignedGetUrl(String objectKey) {
        String normalizedObjectKey = allowedImageObjectKey(objectKey);
        try {
            return UploadDtoAssembler.presignedGetUrlResponse(
                    objectStorageService.createPresignedGetUrl(normalizedObjectKey, presignedGetTtl));
        } catch (IOException e) {
            log.warn("Presigned GET creation failed for object {}", objectKey, e);
            throw uploadFailure(ErrorCode.INTERNAL_ERROR, "presigned get failed");
        }
    }

    private static String allowedImageObjectKey(String objectKey) {
        String normalizedObjectKey = ObjectStorageKey.normalize(objectKey);
        if (normalizedObjectKey.startsWith("avatar/") || normalizedObjectKey.startsWith("product/")) {
            return normalizedObjectKey;
        }
        throw uploadFailure("unsupported object key");
    }

    private BufferedImage cropToSquare(ImageReadResult readResult) throws IOException {
        int size = Math.min(readResult.width(), readResult.height());
        return Thumbnails.of(readResult.image())
                .sourceRegion(Positions.CENTER, size, size)
                .size(size, size)
                .asBufferedImage();
    }

    private static BusinessException uploadFailure(String message) {
        return uploadFailure(ErrorCode.VALIDATION_ERROR, message);
    }

    private static BusinessException uploadFailure(ErrorCode errorCode, String message) {
        return new BusinessException(errorCode, message);
    }

    private ImageFormat detectFormat(UploadFileContent file) throws IOException {
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
        if (header.length >= 3 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) {
            return ImageFormat.JPEG;
        }
        return null;
    }

    private byte[] readHeader(UploadFileContent file) throws IOException {
        byte[] header = new byte[12];
        try (InputStream input = file.openStream()) {
            int read = input.read(header);
            return read <= 0 ? new byte[0] : Arrays.copyOf(header, read);
        }
    }

    private ImageReadResult readImageSafely(UploadFileContent file) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(file.openStream())) {
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

        static ImageFormat fromMediaType(String mediaType) {
            for (ImageFormat format : values()) {
                if (format.mediaType.equalsIgnoreCase(mediaType)) {
                    return format;
                }
            }
            return null;
        }
    }

    @FunctionalInterface
    interface MimeDetector {
        String detect(UploadFileContent file) throws IOException;
    }

    private static final class TikaMimeDetector implements MimeDetector {

        private final Tika tika = new Tika();

        @Override
        public String detect(UploadFileContent file) throws IOException {
            try (InputStream input = file.openStream()) {
                return tika.detect(input);
            }
        }
    }

    private record ImageReadResult(BufferedImage image, int width, int height) {}
}
