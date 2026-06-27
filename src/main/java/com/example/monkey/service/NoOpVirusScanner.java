package com.example.monkey.service;

import org.springframework.web.multipart.MultipartFile;

final class NoOpVirusScanner implements VirusScanner {

    @Override
    public void assertClean(MultipartFile file) {
        // Virus scanning is intentionally disabled for local profiles unless configured.
    }
}
