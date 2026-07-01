package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NoOpVirusScannerTest {

    @Test
    void acceptsContentWithoutScanningWhenDisabled() {
        NoOpVirusScanner scanner = new NoOpVirusScanner();

        assertThatCode(() -> scanner.assertClean(
                        new ByteArrayInputStream("local-profile-upload".getBytes(StandardCharsets.UTF_8))))
                .doesNotThrowAnyException();
    }
}
