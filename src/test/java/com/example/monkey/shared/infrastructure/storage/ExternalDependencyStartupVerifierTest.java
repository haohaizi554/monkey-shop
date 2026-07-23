package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExternalDependencyStartupVerifierTest {

    @Test
    void acceptsExistingBucketAndClamdPong() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        try (FakePingServer clamd = FakePingServer.responding("PONG\n")) {
            ExternalDependencyStartupVerifier verifier =
                    new ExternalDependencyStartupVerifier(minio, "monkeyshop", "127.0.0.1", clamd.port(), 2000);

            assertThatCode(verifier::verifyDependencies).doesNotThrowAnyException();
            assertThat(clamd.awaitCommand()).isEqualTo("nPING");
        }
    }

    @Test
    void rejectsMissingBucket() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        ExternalDependencyStartupVerifier verifier =
                new ExternalDependencyStartupVerifier(minio, "missing", "127.0.0.1", 1, 100);

        assertThatThrownBy(verifier::verifyDependencies)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3 bucket is not available: missing");
    }

    @Test
    void createsMissingBucketWhenExplicitlyEnabled() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        try (FakePingServer clamd = FakePingServer.responding("PONG\n")) {
            ExternalDependencyStartupVerifier verifier =
                    new ExternalDependencyStartupVerifier(minio, "cold-start", true, "127.0.0.1", clamd.port(), 2000);

            assertThatCode(verifier::verifyDependencies).doesNotThrowAnyException();
        }

        verify(minio).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    void rejectsUnexpectedClamdResponse() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        try (FakePingServer clamd = FakePingServer.responding("NOPE\n")) {
            ExternalDependencyStartupVerifier verifier =
                    new ExternalDependencyStartupVerifier(minio, "monkeyshop", "127.0.0.1", clamd.port(), 2000);

            assertThatThrownBy(verifier::verifyDependencies)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ClamAV did not answer PONG");
        }
    }

    private static final class FakePingServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final CompletableFuture<String> command;

        private FakePingServer(ServerSocket serverSocket, CompletableFuture<String> command) {
            this.serverSocket = serverSocket;
            this.command = command;
        }

        static FakePingServer responding(String response) throws IOException {
            ServerSocket serverSocket = new ServerSocket(0);
            CompletableFuture<String> command = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = serverSocket.accept()) {
                    ByteArrayOutputStream received = new ByteArrayOutputStream();
                    int next;
                    while ((next = socket.getInputStream().read()) != -1 && next != '\n') {
                        received.write(next);
                    }
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return received.toString(StandardCharsets.US_ASCII);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            return new FakePingServer(serverSocket, command);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        String awaitCommand() throws Exception {
            return command.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
