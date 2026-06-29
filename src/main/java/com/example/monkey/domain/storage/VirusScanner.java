package com.example.monkey.domain.storage;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface VirusScanner {

    void assertClean(InputStream content) throws IOException;
}
