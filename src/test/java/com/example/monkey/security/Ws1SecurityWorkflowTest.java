package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws1SecurityWorkflowTest {

    @Test
    void workflowRunsFastGateAndFullDependencyCheck() throws IOException {
        String workflow = Files.readString(
                Path.of(".github/workflows/ws1-security.yml"), StandardCharsets.UTF_8);

        assertThat(workflow).contains("fetch-depth: 0");
        assertThat(workflow).contains(".\\scripts\\verify-ws1-security.ps1 -SkipDependencyCheck");
        assertThat(workflow).contains("NVD_API_KEY: ${{ secrets.NVD_API_KEY }}");
        assertThat(workflow).contains("Set repository secret NVD_API_KEY");
        assertThat(workflow).contains("mvn --batch-mode clean verify");
    }
}
