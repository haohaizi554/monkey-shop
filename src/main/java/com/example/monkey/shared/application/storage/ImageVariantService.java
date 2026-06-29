package com.example.monkey.shared.application.storage;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.storage.ObjectStorageKey;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ImageVariantService {

    private static final Logger log = LoggerFactory.getLogger(ImageVariantService.class);
    private static final String VARIANT_MARKER = "@";

    private final ObjectStorageService objectStorageService;
    private final boolean enabled;
    private final List<VariantFormat> formats;
    private final List<Integer> widths;
    private final boolean failOnMissingEncoder;

    public ImageVariantService(
            ObjectStorageService objectStorageService,
            @Value("${app.upload.variants.enabled:true}") boolean enabled,
            @Value("${app.upload.variants.formats:webp,avif}") String formats,
            @Value("${app.upload.variants.widths:320,640}") String widths,
            @Value("${app.upload.variants.fail-on-missing-encoder:false}") boolean failOnMissingEncoder) {
        this(objectStorageService, enabled, parseFormats(formats), parseWidths(widths), failOnMissingEncoder);
    }

    ImageVariantService(
            ObjectStorageService objectStorageService,
            boolean enabled,
            List<VariantFormat> formats,
            List<Integer> widths,
            boolean failOnMissingEncoder) {
        this.objectStorageService = objectStorageService;
        this.enabled = enabled;
        this.formats = List.copyOf(formats);
        this.widths = List.copyOf(widths);
        this.failOnMissingEncoder = failOnMissingEncoder;
    }

    static ImageVariantService disabled(ObjectStorageService objectStorageService) {
        return new ImageVariantService(objectStorageService, false, List.of(), List.of(), false);
    }

    public Map<String, String> createVariants(BufferedImage source, String canonicalObjectKey) throws IOException {
        if (!enabled || source == null || formats.isEmpty() || widths.isEmpty()) {
            return Map.of();
        }

        Map<String, String> variants = new LinkedHashMap<>();
        for (VariantFormat format : formats) {
            if (!hasEncoder(format)) {
                handleMissingEncoder(format);
                continue;
            }
            for (int width : widths) {
                int targetWidth = Math.min(width, Math.max(source.getWidth(), source.getHeight()));
                if (targetWidth <= 0) {
                    continue;
                }
                String variantObjectKey = variantObjectKey(canonicalObjectKey, targetWidth, format);
                byte[] content = encodeVariant(source, targetWidth, format);
                ObjectStorageService.StoredObject storedObject =
                        objectStorageService.store(variantObjectKey, content, format.mediaType());
                variants.put(format.extension() + "-" + targetWidth + "w", storedObject.publicUrl());
            }
        }
        return Map.copyOf(variants);
    }

    public static String canonicalPathForVariant(String imagePath) {
        if (!StringUtils.hasText(imagePath)) {
            return imagePath;
        }
        int marker = imagePath.lastIndexOf(VARIANT_MARKER);
        if (marker < 0) {
            return imagePath;
        }
        String canonical = imagePath.substring(0, marker);
        String lower = canonical.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return canonical;
        }
        return imagePath;
    }

    public static boolean isVariantPath(String imagePath) {
        return StringUtils.hasText(imagePath)
                && !canonicalPathForVariant(imagePath).equals(imagePath);
    }

    public static String variantGlob(String canonicalFileName) {
        return canonicalFileName + VARIANT_MARKER + "*.*";
    }

    private byte[] encodeVariant(BufferedImage source, int targetWidth, VariantFormat format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thumbnails.of(source)
                .size(targetWidth, targetWidth)
                .keepAspectRatio(true)
                .outputFormat(format.imageIoName())
                .toOutputStream(output);
        return output.toByteArray();
    }

    private void handleMissingEncoder(VariantFormat format) {
        String message = "No ImageIO encoder is available for " + format.imageIoName();
        if (failOnMissingEncoder) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, message);
        }
        log.warn("{}; skipping {} image variants", message, format.extension());
    }

    private static boolean hasEncoder(VariantFormat format) {
        return ImageIO.getImageWritersByFormatName(format.imageIoName()).hasNext();
    }

    private static String variantObjectKey(String canonicalObjectKey, int width, VariantFormat format) {
        return ObjectStorageKey.normalize(canonicalObjectKey) + VARIANT_MARKER + width + "w." + format.extension();
    }

    private static List<VariantFormat> parseFormats(String configuredFormats) {
        if (!StringUtils.hasText(configuredFormats)) {
            return List.of();
        }
        List<VariantFormat> parsed = new ArrayList<>();
        Arrays.stream(configuredFormats.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(VariantFormat::fromConfigValue)
                .forEach(parsed::add);
        return parsed;
    }

    private static List<Integer> parseWidths(String configuredWidths) {
        if (!StringUtils.hasText(configuredWidths)) {
            return List.of();
        }
        return Arrays.stream(configuredWidths.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(Integer::parseInt)
                .filter(width -> width > 0)
                .distinct()
                .toList();
    }

    enum VariantFormat {
        WEBP("webp", "webp", "image/webp"),
        AVIF("avif", "avif", "image/avif");

        private final String extension;
        private final String imageIoName;
        private final String mediaType;

        VariantFormat(String extension, String imageIoName, String mediaType) {
            this.extension = extension;
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

        static VariantFormat fromConfigValue(String value) {
            String normalized = value.toLowerCase(Locale.ROOT);
            for (VariantFormat format : values()) {
                if (format.extension.equals(normalized)) {
                    return format;
                }
            }
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "unsupported image variant format");
        }
    }
}
