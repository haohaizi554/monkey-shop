package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.storage.VirusScanner;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.upload.virus-scan.enabled", havingValue = "false", matchIfMissing = true)
public final class NoOpVirusScanner implements VirusScanner {

    @Override
    public void assertClean(InputStream content) {
        // Virus scanning is intentionally disabled for local profiles unless configured.
    }
}
