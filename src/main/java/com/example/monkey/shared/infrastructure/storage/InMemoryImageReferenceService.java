package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.storage.ImageReferenceService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(ImageReferenceService.class)
public class InMemoryImageReferenceService implements ImageReferenceService {

    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    @Override
    public void retain(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        counts.computeIfAbsent(imagePath, ignored -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void release(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        counts.computeIfPresent(imagePath, (ignored, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    @Override
    public long referenceCount(String imagePath) {
        AtomicLong count = counts.get(imagePath);
        return count == null ? 0L : Math.max(0L, count.get());
    }

    @Override
    public void clear() {
        counts.clear();
    }
}
