package com.example.monkey.shared.application.storage;

import java.io.IOException;
import java.io.InputStream;

public interface UploadFileContent {

    boolean isEmpty();

    long size();

    InputStream openStream() throws IOException;
}
