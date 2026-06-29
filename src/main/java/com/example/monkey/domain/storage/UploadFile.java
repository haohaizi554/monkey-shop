package com.example.monkey.domain.storage;

import java.io.IOException;
import java.io.InputStream;

public interface UploadFile {

    boolean isEmpty();

    long size();

    InputStream openStream() throws IOException;
}
