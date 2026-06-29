package com.example.monkey.shared.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.application.storage.UploadFileContent;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class MultipartUploadFileTest {

    @Test
    void adaptsMultipartFileToApplicationUploadContent() throws IOException {
        MockMultipartFile multipartFile = new MockMultipartFile("file", "image.png", "image/png", new byte[] {1, 2, 3});

        UploadFileContent uploadFile = MultipartUploadFile.from(multipartFile);

        assertThat(uploadFile).isNotNull();
        assertThat(uploadFile.isEmpty()).isFalse();
        assertThat(uploadFile.size()).isEqualTo(3);
        assertThat(uploadFile.openStream()).hasBinaryContent(new byte[] {1, 2, 3});
    }

    @Test
    void nullMultipartFileStaysNull() {
        assertThat(MultipartUploadFile.from(null)).isNull();
    }
}
