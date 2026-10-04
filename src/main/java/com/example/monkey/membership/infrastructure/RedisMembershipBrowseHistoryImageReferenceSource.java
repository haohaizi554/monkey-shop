package com.example.monkey.membership.infrastructure;

import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.StoredImageReferenceSource;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(420)
public class RedisMembershipBrowseHistoryImageReferenceSource implements StoredImageReferenceSource {

    private final RedisMembershipActivityStore activityStore;

    public RedisMembershipBrowseHistoryImageReferenceSource(RedisMembershipActivityStore activityStore) {
        this.activityStore = Objects.requireNonNull(activityStore, "activityStore");
    }

    @Override
    public boolean isUsed(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return false;
        }
        String canonicalPath = canonicalPath(imagePath);
        AtomicBoolean used = new AtomicBoolean();
        readStrict(item -> {
            if (sameLogicalImage(item.productImage(), canonicalPath)) {
                used.set(true);
            }
        });
        return used.get();
    }

    @Override
    public void forEachReferencedImagePath(Consumer<String> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        readStrict(item -> {
            String productImage = item.productImage();
            if (ImageReferenceService.isTrackable(productImage)) {
                consumer.accept(productImage.trim());
            }
        });
    }

    private void readStrict(Consumer<BrowseHistoryItem> consumer) {
        try {
            activityStore.forEachLiveBrowseHistoryItem(consumer);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Unable to read Redis membership browse history image references", failure);
        }
    }

    private static boolean sameLogicalImage(String productImage, String canonicalPath) {
        return ImageReferenceService.isTrackable(productImage)
                && canonicalPath(productImage).equals(canonicalPath);
    }

    private static String canonicalPath(String imagePath) {
        return ImageReferenceService.canonicalPath(imagePath.trim());
    }
}
