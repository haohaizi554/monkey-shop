package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.storage.MalwareDetectedException;
import com.example.monkey.shared.domain.storage.VirusScanner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.upload.virus-scan.enabled", havingValue = "true")
public final class ClamAvVirusScanner implements VirusScanner {

    private static final int CHUNK_SIZE = 8192;
    private static final int MAX_RESPONSE_BYTES = 4096;

    private final String host;
    private final int port;
    private final int timeoutMillis;

    public ClamAvVirusScanner(
            @Value("${app.upload.virus-scan.host:127.0.0.1}") String host,
            @Value("${app.upload.virus-scan.port:3310}") int port,
            @Value("${app.upload.virus-scan.timeout-millis:5000}") int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void assertClean(InputStream content) throws IOException {
        // ClamAV clamd speaks plaintext INSTREAM; keep this endpoint on localhost or a private sidecar network.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            OutputStream output = socket.getOutputStream();
            output.write("nINSTREAM\n".getBytes(StandardCharsets.US_ASCII));
            streamContent(content, output);
            writeChunkLength(output, 0);
            output.flush();
            assertCleanResponse(readResponse(socket.getInputStream()));
        }
    }

    private static void streamContent(InputStream input, OutputStream output) throws IOException {
        byte[] chunk = new byte[CHUNK_SIZE];
        int read;
        while ((read = input.read(chunk)) != -1) {
            writeChunkLength(output, read);
            output.write(chunk, 0, read);
        }
    }

    private static void writeChunkLength(OutputStream output, int length) throws IOException {
        output.write((length >>> 24) & 0xFF);
        output.write((length >>> 16) & 0xFF);
        output.write((length >>> 8) & 0xFF);
        output.write(length & 0xFF);
    }

    private static String readResponse(InputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n' || next == 0) {
                break;
            }
            if (response.size() >= MAX_RESPONSE_BYTES) {
                throw new IOException("ClamAV response exceeded maximum length");
            }
            response.write(next);
        }
        if (response.size() == 0 && next == -1) {
            throw new IOException("ClamAV closed the connection without a response");
        }
        return response.toString(StandardCharsets.UTF_8);
    }

    static void assertCleanResponse(String response) throws IOException {
        String normalized = response == null ? "" : response.trim();
        if (normalized.endsWith(" OK")) {
            return;
        }
        if (normalized.endsWith(" FOUND")) {
            throw new MalwareDetectedException(normalized);
        }
        if (normalized.endsWith(" ERROR")) {
            throw new IOException("ClamAV error response: " + normalized);
        }
        throw new IOException("Unexpected ClamAV response: " + normalized);
    }
}
