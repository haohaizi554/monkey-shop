package com.example.monkey.membership;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws8MembershipWorkflowTest {

    @Test
    void membershipArtifactsWireLevelWalletCollectionBrowsingAndFrontend() throws IOException {
        String docs = read("docs/membership/ws8.md");
        String service =
                read("src/main/java/com/example/monkey/membership/application/MembershipApplicationService.java");
        String controller = read("src/main/java/com/example/monkey/membership/interfaces/MembershipController.java");
        String migration = read("src/main/resources/db/migration/V35__membership_points_wallet.sql");
        String frontend = read("frontend/src/views/MembershipView.vue");
        String script = read("scripts/verify-ws8-membership.ps1");

        assertThat(docs).contains("Points wallet", "Browsing history", "TOTP");
        assertThat(service)
                .contains("@WithSpan(\"membership.check-in\")", "scanPriceDrops", "userMfaVerifier.verifyCode");
        assertThat(controller).contains("/api/membership", "/points/redeem", "/collections");
        assertThat(migration).contains("membership_points_wallet", "membership_points_ledger");
        assertThat(frontend).contains("membershipApi.checkIn", "scanPriceDrops");
        assertThat(script)
                .contains(
                        "JpaMembershipStoreTest",
                        "SecurityConfigTest",
                        "WS8 membership verification completed successfully");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
