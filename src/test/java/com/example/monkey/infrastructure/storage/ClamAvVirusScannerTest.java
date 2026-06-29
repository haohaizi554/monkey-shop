package com.example.monkey.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.domain.storage.MalwareDetectedException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

    @Test
    void streamsFileToClamdAndAcceptsOkResponse() throws Exception {
        try (FakeClamdServer server = FakeClamdServer.responding("stream: OK\n")) {
            ClamAvVirusScanner scanner = new ClamAvVirusScanner("127.0.0.1", server.port(), 2000);

            scanner.assertClean(new ByteArrayInputStream("hello-clamd".getBytes(StandardCharsets.UTF_8)));

            assertThatCode(() -> server.awaitPayload()).doesNotThrowAnyException();
            org.assertj.core.api.Assertions.assertThat(server.awaitPayload()).isEqualTo("hello-clamd");
            org.assertj.core.api.Assertions.assertThat(server.awaitCommand()).isEqualTo("nINSTREAM");
        }
    }

    @Test
    void assertCleanRejectsFoundResponseFromClamd() throws Exception {
        try (FakeClamdServer server = FakeClamdServer.responding("stream: Eicar-Test-Signature FOUND\n")) {
            ClamAvVirusScanner scanner = new ClamAvVirusScanner("127.0.0.1", server.port(), 2000);

            assertThatThrownBy(
                            () -> scanner.assertClean(new ByteArrayInputStream("bad".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(MalwareDetectedException.class)
                    .hasMessageContaining("FOUND");
        }
    }

    @Test
    void oversizedClamdResponseIsRejected() throws Exception {
        String oversizedResponse = "stream: " + "x".repeat(4096) + "\n";
        try (FakeClamdServer server = FakeClamdServer.responding(oversizedResponse)) {
            ClamAvVirusScanner scanner = new ClamAvVirusScanner("127.0.0.1", server.port(), 2000);

            assertThatThrownBy(() ->
                            scanner.assertClean(new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("ClamAV response exceeded maximum length");
        }
    }

    private static final class FakeClamdServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final CompletableFuture<Exchange> exchange;

        private FakeClamdServer(ServerSocket serverSocket, CompletableFuture<Exchange> exchange) {
            this.serverSocket = serverSocket;
            this.exchange = exchange;
        }

        static FakeClamdServer responding(String response) throws IOException {
            ServerSocket serverSocket = new ServerSocket(0);
            CompletableFuture<Exchange> exchange = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = serverSocket.accept()) {
                    InputStream input = socket.getInputStream();
                    String command = readCommand(input);
                    String payload = readPayload(input);
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    return new Exchange(command, payload);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            return new FakeClamdServer(serverSocket, exchange);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        String awaitCommand() throws Exception {
            return exchange.get(5, TimeUnit.SECONDS).command();
        }

        String awaitPayload() throws Exception {
            return exchange.get(5, TimeUnit.SECONDS).payload();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }

        private static String readCommand(InputStream input) throws IOException {
            ByteArrayOutputStream command = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) != -1 && next != '\n') {
                command.write(next);
            }
            return command.toString(StandardCharsets.US_ASCII);
        }

        private static String readPayload(InputStream input) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            while (true) {
                int length = readLength(input);
                if (length == 0) {
                    return payload.toString(StandardCharsets.UTF_8);
                }
                payload.write(input.readNBytes(length));
            }
        }

        private static int readLength(InputStream input) throws IOException {
            byte[] bytes = input.readNBytes(4);
            if (bytes.length != 4) {
                throw new IOException("missing clamd chunk length");
            }
            return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        }
    }

    private record Exchange(String command, String payload) {}
}
