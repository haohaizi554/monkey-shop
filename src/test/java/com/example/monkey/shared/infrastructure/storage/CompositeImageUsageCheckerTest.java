package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.domain.storage.StoredImageReferenceSource;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CompositeImageUsageCheckerTest {

    @Test
    void reportsUsedWhenAnyReferenceSourceUsesImage() {
        TestImageReferenceSource productSource = new TestImageReferenceSource(false);
        TestImageReferenceSource orderSource = new TestImageReferenceSource(true);
        TestImageReferenceSource userSource = new TestImageReferenceSource(true);
        CompositeImageUsageChecker checker =
                new CompositeImageUsageChecker(List.of(productSource, orderSource, userSource));

        boolean used = checker.isUsed("/images/product/used.png");

        assertThat(used).isTrue();
        assertThat(productSource.usageChecks()).isEqualTo(1);
        assertThat(orderSource.usageChecks()).isEqualTo(1);
        assertThat(userSource.usageChecks()).isZero();
    }

    @Test
    void reportsUnusedWhenNoReferenceSourceUsesImage() {
        TestImageReferenceSource productSource = new TestImageReferenceSource(false);
        TestImageReferenceSource orderSource = new TestImageReferenceSource(false);
        CompositeImageUsageChecker checker = new CompositeImageUsageChecker(List.of(productSource, orderSource));

        boolean used = checker.isUsed("/images/product/free.png");

        assertThat(used).isFalse();
        assertThat(productSource.usageChecks()).isEqualTo(1);
        assertThat(orderSource.usageChecks()).isEqualTo(1);
    }

    private static final class TestImageReferenceSource implements StoredImageReferenceSource {

        private final boolean used;
        private int usageChecks;

        private TestImageReferenceSource(boolean used) {
            this.used = used;
        }

        @Override
        public boolean isUsed(String imagePath) {
            usageChecks++;
            return used;
        }

        @Override
        public void forEachReferencedImagePath(Consumer<String> consumer) {}

        private int usageChecks() {
            return usageChecks;
        }
    }
}
