package com.example.monkey.security;

import com.example.monkey.domain.user.PasswordCompromiseChecker;
import com.example.monkey.domain.user.PasswordCompromiseChecker.PasswordCompromiseCheckResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PwnedPasswordChecker implements PasswordCompromiseChecker {

    private static final Logger log = LoggerFactory.getLogger(PwnedPasswordChecker.class);
    private static final String USER_AGENT = "MonkeyShop-Security";
    private static final String ADD_PADDING_HEADER = "Add-Padding";
    private static final int SHA1_PREFIX_LENGTH = 5;

    private final boolean enabled;
    private final String endpoint;
    private final RangeClient rangeClient;

    public PwnedPasswordChecker(
            @Value("${app.security.password.hibp.enabled:true}") boolean enabled,
            @Value("${app.security.password.hibp.endpoint:https://api.pwnedpasswords.com/range}") String endpoint,
            @Value("${app.security.password.hibp.timeout-millis:3000}") long timeoutMillis) {
        this(
                enabled,
                endpoint,
                normalizedTimeout(Duration.ofMillis(timeoutMillis)),
                new HttpRangeClient(normalizedTimeout(Duration.ofMillis(timeoutMillis))));
    }

    PwnedPasswordChecker(boolean enabled, String endpoint, Duration timeout, RangeClient rangeClient) {
        this.enabled = enabled;
        this.endpoint = normalizeEndpoint(endpoint);
        this.rangeClient = rangeClient != null ? rangeClient : new HttpRangeClient(normalizedTimeout(timeout));
    }

    @Override
    public PasswordCompromiseCheckResult check(String password) {
        if (!enabled || !StringUtils.hasText(password)) {
            return PasswordCompromiseCheckResult.safe();
        }

        String sha1 = sha1Hex(password);
        String prefix = sha1.substring(0, SHA1_PREFIX_LENGTH);
        String suffix = sha1.substring(SHA1_PREFIX_LENGTH);
        try {
            String rangeResponse = rangeClient.fetch(prefix, rangeUri(prefix));
            return responseContainsSuffix(rangeResponse, suffix)
                    ? PasswordCompromiseCheckResult.compromisedPassword()
                    : PasswordCompromiseCheckResult.safe();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Pwned password range check was interrupted");
            return PasswordCompromiseCheckResult.unavailable();
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Pwned password range check is unavailable: {}", e.toString());
            return PasswordCompromiseCheckResult.unavailable();
        }
    }

    private URI rangeUri(String prefix) {
        return URI.create(endpoint + "/" + prefix);
    }

    private static String normalizeEndpoint(String endpoint) {
        String normalized = StringUtils.hasText(endpoint) ? endpoint.trim() : "https://api.pwnedpasswords.com/range";
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Duration normalizedTimeout(Duration timeout) {
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(3) : timeout;
    }

    private static String sha1Hex(String password) {
        try {
            // HIBP range lookups require SHA-1; this transient digest is not used for password storage.
            // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().withUpperCase().formatHex(digest.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 digest must be available in the JDK", e);
        }
    }

    static boolean responseContainsSuffix(String rangeResponse, String expectedSuffix) {
        if (rangeResponse == null) {
            return false;
        }
        for (String line : rangeResponse.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String suffix = line.substring(0, separator).trim();
            if (expectedSuffix.equalsIgnoreCase(suffix)) {
                String count = line.substring(separator + 1).trim();
                if (!StringUtils.hasText(count)) {
                    return true;
                }
                try {
                    return Long.parseLong(count) > 0;
                } catch (NumberFormatException e) {
                    return true;
                }
            }
        }
        return false;
    }

    @FunctionalInterface
    interface RangeClient {
        String fetch(String prefix, URI uri) throws IOException, InterruptedException;
    }

    private static final class HttpRangeClient implements RangeClient {

        private final HttpClient httpClient;
        private final Duration timeout;

        private HttpRangeClient(Duration timeout) {
            this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
            this.timeout = timeout;
        }

        @Override
        public String fetch(String prefix, URI uri) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header(ADD_PADDING_HEADER, "true")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected HIBP range API status: " + response.statusCode());
            }
            return response.body();
        }
    }
}
