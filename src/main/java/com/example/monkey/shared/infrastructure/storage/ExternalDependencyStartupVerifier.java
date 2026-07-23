package com.example.monkey.shared.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.integrations.startup-readiness-required", havingValue = "true")
public final class ExternalDependencyStartupVerifier {

    private static final byte[] CLAMD_PING = "nPING\n".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_CLAMD_RESPONSE_BYTES = 64;

    private final MinioClient minioClient;
    private final String bucket;
    private final boolean createStorageBucket;
    private final String clamHost;
    private final int clamPort;
    private final int timeoutMillis;

    @Autowired
    public ExternalDependencyStartupVerifier(
            @Value("${app.storage.minio.endpoint}") String minioEndpoint,
            @Value("${app.storage.minio.access-key}") String minioAccessKey,
            @Value("${app.storage.minio.secret-key}") String minioSecretKey,
            @Value("${app.storage.minio.bucket}") String bucket,
            @Value("${app.integrations.startup-create-storage-bucket:false}") boolean createStorageBucket,
            @Value("${app.upload.virus-scan.host:127.0.0.1}") String clamHost,
            @Value("${app.upload.virus-scan.port:3310}") int clamPort,
            @Value("${app.upload.virus-scan.timeout-millis:5000}") int timeoutMillis) {
        this(
                createMinioClient(minioEndpoint, minioAccessKey, minioSecretKey),
                bucket,
                createStorageBucket,
                clamHost,
                clamPort,
                timeoutMillis);
    }

    ExternalDependencyStartupVerifier(
            MinioClient minioClient, String bucket, String clamHost, int clamPort, int timeoutMillis) {
        this(minioClient, bucket, false, clamHost, clamPort, timeoutMillis);
    }

    ExternalDependencyStartupVerifier(
            MinioClient minioClient,
            String bucket,
            boolean createStorageBucket,
            String clamHost,
            int clamPort,
            int timeoutMillis) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.createStorageBucket = createStorageBucket;
        this.clamHost = clamHost;
        this.clamPort = clamPort;
        this.timeoutMillis = timeoutMillis;
    }

    @PostConstruct
    public void verifyDependencies() {
        verifyBucket();
        verifyClamAv();
    }

    private void verifyBucket() {
        final boolean bucketExists;
        try {
            bucketExists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception exception) {
            throw new IllegalStateException("S3 bucket readiness check failed: " + bucket, exception);
        }
        if (!bucketExists) {
            if (!createStorageBucket) {
                throw new IllegalStateException("S3 bucket is not available: " + bucket);
            }
            try {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            } catch (Exception exception) {
                throw new IllegalStateException("S3 bucket provisioning failed: " + bucket, exception);
            }
        }
    }

    private void verifyClamAv() {
        final String response;
        // ClamAV clamd speaks plaintext PING; keep this readiness endpoint on localhost or a private sidecar network.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(clamHost, clamPort), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.getOutputStream().write(CLAMD_PING);
            socket.getOutputStream().flush();
            response = readClamAvResponse(socket.getInputStream());
        } catch (SocketTimeoutException exception) {
            throw new IllegalStateException("ClamAV readiness check timed out at " + clamAddress(), exception);
        } catch (Exception exception) {
            throw new IllegalStateException("ClamAV readiness check failed at " + clamAddress(), exception);
        }

        if (!"PONG".equals(response)) {
            throw new IllegalStateException("ClamAV did not answer PONG at " + clamAddress());
        }
    }

    private static String readClamAvResponse(InputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n' || next == 0) {
                break;
            }
            if (response.size() >= MAX_CLAMD_RESPONSE_BYTES) {
                throw new IOException("ClamAV response exceeded maximum length");
            }
            response.write(next);
        }
        return response.toString(StandardCharsets.US_ASCII);
    }

    private String clamAddress() {
        return clamHost + ":" + clamPort;
    }

    private static MinioClient createMinioClient(String endpoint, String accessKey, String secretKey) {
        try {
            return MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("S3 client configuration is invalid", exception);
        }
    }
}
