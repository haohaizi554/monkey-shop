package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ClamAvVirusScannerTest {

    @Test
    void acceptsCleanClamdResponse() {
        assertThatCode(() -> ClamAvVirusScanner.assertCleanResponse("stream: OK"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsFoundClamdResponse() {
        assertThatThrownBy(() -> ClamAvVirusScanner.assertCleanResponse("stream: Eicar-Test-Signature FOUND"))
                .isInstanceOf(MalwareDetectedException.class)
                .hasMessageContaining("FOUND");
    }

    @Test
    void rejectsErrorClamdResponse() {
        assertThatThrownBy(() -> ClamAvVirusScanner.assertCleanResponse("stream: scan failed ERROR"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ClamAV error response");
    }
}
