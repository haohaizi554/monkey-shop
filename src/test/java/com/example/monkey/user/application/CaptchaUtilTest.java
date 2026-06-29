package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CaptchaUtilTest {

    @Test
    void generatedCodesUseExpectedLengthAndAlphabet() {
        for (int i = 0; i < 100; i++) {
            assertThat(CaptchaUtil.generateCode()).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}");
        }
    }

    @Test
    void captchaGenerationUsesSecureRandom() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/example/monkey/user/application/CaptchaUtil.java"), StandardCharsets.UTF_8);

        assertThat(source).contains("java.security.SecureRandom");
        assertThat(source).doesNotContain("java.util." + "Random");
        assertThat(source).doesNotContain("new " + "Random");
    }
}
