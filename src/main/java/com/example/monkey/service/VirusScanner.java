package com.example.monkey.service;

import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

@FunctionalInterface
interface VirusScanner {

    void assertClean(MultipartFile file) throws IOException;
}
