package com.example.monkey.shared.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.monkey.shared.domain.storage.MalwareDetectedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class LocalProductionSupportAcceptanceTest {

    private static final String ACCEPTANCE_FLAG = "MONKEYSHOP_LOCAL_SUPPORT_ACCEPTANCE";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long MINIO_CONNECT_TIMEOUT_MILLIS = 5_000L;
    private static final long MINIO_WRITE_TIMEOUT_MILLIS = 10_000L;
    private static final long MINIO_READ_TIMEOUT_MILLIS = 10_000L;
    private static final int PRESIGNED_EXPIRY_SECONDS = 60;
    private static final int CLAMAV_TIMEOUT_MILLIS = 5_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void verifiesLocalProductionSupportDependencies() throws Exception {
        Assumptions.assumeTrue(
                "true".equals(System.getenv(ACCEPTANCE_FLAG)), "Local production support acceptance is disabled");
        SupportEnvironment environment = SupportEnvironment.fromEnvironment();

        try (HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            verifyStartupBucketProvisioning(environment.minio(), environment.clamAv());
            verifyMinio(environment.minio(), httpClient);
            verifyClamAv(environment.clamAv());
            verifyVault(environment.vault(), httpClient);
        }
    }

    private static void verifyStartupBucketProvisioning(
            MinioEnvironment minioEnvironment, ClamAvEnvironment clamAvEnvironment) throws Exception {
        String bucket = "monkeyshop-startup-" + UUID.randomUUID().toString().replace("-", "");
        try (MinioClient minioClient = MinioClient.builder()
                .endpoint(minioEnvironment.endpoint())
                .credentials(minioEnvironment.accessKey(), minioEnvironment.secretKey())
                .build()) {
            minioClient.setTimeout(MINIO_CONNECT_TIMEOUT_MILLIS, MINIO_WRITE_TIMEOUT_MILLIS, MINIO_READ_TIMEOUT_MILLIS);
            assertTrue(
                    !minioClient.bucketExists(
                            BucketExistsArgs.builder().bucket(bucket).build()),
                    "Startup acceptance bucket must begin absent");

            ExternalDependencyStartupVerifier verifier = new ExternalDependencyStartupVerifier(
                    minioClient,
                    bucket,
                    true,
                    clamAvEnvironment.host(),
                    clamAvEnvironment.port(),
                    CLAMAV_TIMEOUT_MILLIS);
            try {
                verifier.verifyDependencies();
                assertTrue(
                        minioClient.bucketExists(
                                BucketExistsArgs.builder().bucket(bucket).build()),
                        "Startup verifier did not provision the configured bucket");
            } finally {
                if (minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build())) {
                    minioClient.removeBucket(
                            RemoveBucketArgs.builder().bucket(bucket).build());
                }
            }
        }
    }

    private static void verifyMinio(MinioEnvironment environment, HttpClient httpClient) throws Exception {
        String objectKey = "acceptance/local-production-support/" + UUID.randomUUID() + ".bin";
        byte[] expected = ("MonkeyShop MinIO acceptance " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        try (MinioClient minioClient = MinioClient.builder()
                .endpoint(environment.endpoint())
                .credentials(environment.accessKey(), environment.secretKey())
                .build()) {
            minioClient.setTimeout(MINIO_CONNECT_TIMEOUT_MILLIS, MINIO_WRITE_TIMEOUT_MILLIS, MINIO_READ_TIMEOUT_MILLIS);
            if (!minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(environment.bucket()).build())) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(environment.bucket()).build());
            }

            boolean cleanupRequired = false;
            try {
                cleanupRequired = true;
                try (ByteArrayInputStream content = new ByteArrayInputStream(expected)) {
                    minioClient.putObject(
                            PutObjectArgs.builder()
                                    .bucket(environment.bucket())
                                    .object(objectKey)
                                    .contentType(CONTENT_TYPE)
                                    .stream(content, (long) expected.length, -1L)
                                    .build());
                }

                StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                        .bucket(environment.bucket())
                        .object(objectKey)
                        .build());
                assertEquals(expected.length, stat.size(), "MinIO object size mismatch");
                assertEquals(CONTENT_TYPE, stat.contentType(), "MinIO object content type mismatch");

                try (GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                        .bucket(environment.bucket())
                        .object(objectKey)
                        .build())) {
                    assertArrayEquals(expected, response.readAllBytes(), "MinIO get payload mismatch");
                }

                String presignedUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(environment.bucket())
                        .object(objectKey)
                        .expiry(PRESIGNED_EXPIRY_SECONDS)
                        .build());
                HttpRequest request = HttpRequest.newBuilder(safeUri(presignedUrl, "MinIO presigned URL is invalid"))
                        .timeout(HTTP_REQUEST_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = send(httpClient, request);
                try (InputStream body = response.body()) {
                    assertSuccessful(response.statusCode(), "MinIO presigned GET");
                    assertArrayEquals(expected, body.readAllBytes(), "MinIO presigned GET payload mismatch");
                }

                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(environment.bucket())
                        .object(objectKey)
                        .build());
                cleanupRequired = false;
            } finally {
                if (cleanupRequired) {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(environment.bucket())
                            .object(objectKey)
                            .build());
                }
            }
        }
    }

    private static void verifyClamAv(ClamAvEnvironment environment) throws Exception {
        ClamAvVirusScanner scanner =
                new ClamAvVirusScanner(environment.host(), environment.port(), CLAMAV_TIMEOUT_MILLIS);
        byte[] clean = ("MonkeyShop clean acceptance " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        try (ByteArrayInputStream content = new ByteArrayInputStream(clean)) {
            scanner.assertClean(content);
        }

        byte[] eicar = String.join("", "X5O!P%@AP[4\\PZX54(P^)7CC)7}$", "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!", "$H+H*")
                .getBytes(StandardCharsets.US_ASCII);
        try {
            assertThrows(
                    MalwareDetectedException.class,
                    () -> {
                        try (ByteArrayInputStream content = new ByteArrayInputStream(eicar)) {
                            scanner.assertClean(content);
                        }
                    },
                    "ClamAV must reject the EICAR acceptance payload");
        } finally {
            Arrays.fill(eicar, (byte) 0);
        }
    }

    private static void verifyVault(VaultEnvironment environment, HttpClient httpClient) throws Exception {
        verifyVaultDecrypt(httpClient, environment, environment.aesCiphertext(), "Vault AES decrypt");
        verifyVaultDecrypt(httpClient, environment, environment.hmacCiphertext(), "Vault HMAC decrypt");

        String probe =
                Base64.getEncoder().encodeToString("MonkeyShop denied encrypt probe".getBytes(StandardCharsets.UTF_8));
        byte[] requestBody = vaultRequestBody("plaintext", probe, "Vault encrypt request could not be encoded");
        try {
            HttpRequest request = vaultRequest(environment, "encrypt", requestBody);
            HttpResponse<InputStream> response = send(httpClient, request);
            try (InputStream body = response.body()) {
                assertEquals(403, response.statusCode(), "Vault decrypt-only token must be denied encrypt access");
            }
        } finally {
            Arrays.fill(requestBody, (byte) 0);
        }
    }

    private static void verifyVaultDecrypt(
            HttpClient httpClient, VaultEnvironment environment, String ciphertext, String operation) throws Exception {
        byte[] requestBody = vaultRequestBody("ciphertext", ciphertext, operation + " request could not be encoded");
        try {
            HttpRequest request = vaultRequest(environment, "decrypt", requestBody);
            HttpResponse<InputStream> response = send(httpClient, request);
            try (InputStream body = response.body()) {
                assertSuccessful(response.statusCode(), operation);
                JsonNode responseJson = readVaultJson(body, operation);
                JsonNode plaintext = responseJson.path("data").path("plaintext");
                assertTrue(
                        plaintext.isTextual() && !plaintext.textValue().isBlank(), operation + " response is invalid");
                byte[] decoded = decodeVaultPlaintext(plaintext, operation);
                try {
                    assertEquals(32, decoded.length, operation + " plaintext length mismatch");
                } finally {
                    Arrays.fill(decoded, (byte) 0);
                }
            }
        } finally {
            Arrays.fill(requestBody, (byte) 0);
        }
    }

    private static HttpRequest vaultRequest(VaultEnvironment environment, String operation, byte[] requestBody) {
        try {
            return HttpRequest.newBuilder(vaultUri(environment, operation))
                    .timeout(HTTP_REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Vault-Token", environment.token())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
        } catch (IllegalArgumentException ignored) {
            throw new AssertionError("Vault request configuration is invalid");
        }
    }

    private static URI vaultUri(VaultEnvironment environment, String operation) {
        String address = environment.address();
        while (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }
        String transitKey = URLEncoder.encode(environment.transitKey(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return safeUri(address + "/v1/transit/" + operation + "/" + transitKey, "Vault Transit URI is invalid");
    }

    private static byte[] vaultRequestBody(String field, String value, String failureMessage) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(Map.of(field, value));
        } catch (IOException ignored) {
            throw new AssertionError(failureMessage);
        }
    }

    private static JsonNode readVaultJson(InputStream body, String operation) {
        try {
            JsonNode json = OBJECT_MAPPER.readTree(body);
            assertTrue(json != null, operation + " response is invalid");
            return json;
        } catch (IOException ignored) {
            throw new AssertionError(operation + " response is not valid JSON");
        }
    }

    private static byte[] decodeVaultPlaintext(JsonNode plaintext, String operation) {
        try {
            return Base64.getDecoder().decode(plaintext.textValue());
        } catch (IllegalArgumentException ignored) {
            throw new AssertionError(operation + " plaintext is not valid base64");
        }
    }

    private static HttpResponse<InputStream> send(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private static URI safeUri(String value, String failureMessage) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ignored) {
            throw new AssertionError(failureMessage);
        }
    }

    private static void assertSuccessful(int statusCode, String operation) {
        assertTrue(statusCode >= 200 && statusCode < 300, () -> operation + " returned HTTP status " + statusCode);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), name + " must be configured");
        return value;
    }

    private static int requiredPort(String name) {
        String value = requiredEnvironment(name);
        int port;
        try {
            port = Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            throw new AssertionError(name + " must be an integer");
        }
        assertTrue(port > 0 && port <= 65_535, name + " must be a valid TCP port");
        return port;
    }

    private record SupportEnvironment(MinioEnvironment minio, ClamAvEnvironment clamAv, VaultEnvironment vault) {

        private static SupportEnvironment fromEnvironment() {
            return new SupportEnvironment(
                    new MinioEnvironment(
                            requiredEnvironment("APP_STORAGE_MINIO_ENDPOINT"),
                            requiredEnvironment("APP_STORAGE_MINIO_ACCESS_KEY"),
                            requiredEnvironment("APP_STORAGE_MINIO_SECRET_KEY"),
                            requiredEnvironment("APP_STORAGE_MINIO_BUCKET")),
                    new ClamAvEnvironment(requiredEnvironment("CLAMAV_HOST"), requiredPort("CLAMAV_PORT")),
                    new VaultEnvironment(
                            requiredEnvironment("APP_PII_VAULT_ADDR"),
                            requiredEnvironment("APP_PII_VAULT_TOKEN"),
                            requiredEnvironment("APP_PII_VAULT_TRANSIT_KEY"),
                            requiredEnvironment("APP_PII_VAULT_AES_CIPHERTEXT"),
                            requiredEnvironment("APP_PII_VAULT_HMAC_CIPHERTEXT")));
        }
    }

    private record MinioEnvironment(String endpoint, String accessKey, String secretKey, String bucket) {}

    private record ClamAvEnvironment(String host, int port) {}

    private record VaultEnvironment(
            String address, String token, String transitKey, String aesCiphertext, String hmacCiphertext) {}
}
