package com.example.monkey.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PiiEncryptionMigrationContractTest {

    @Test
    void sensitiveFieldMigrationOnlyExpandsColumnsForEncryptedPayloads() throws IOException {
        Path migration = Path.of("src/main/resources/db/migration/V54__encrypt_totp_secret.sql");

        assertThat(migration).exists();

        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        assertThat(sql)
                .contains("ALTER TABLE `user`")
                .contains("MODIFY COLUMN `totp_secret` VARCHAR(1024)")
                .contains("ALTER TABLE `order_review`")
                .contains("MODIFY COLUMN `content` VARCHAR(8192)")
                .doesNotContain("UPDATE ")
                .doesNotContain("enc:v1:");
    }
}
