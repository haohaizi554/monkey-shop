package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.PasswordResetDeliveryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConfigurablePasswordResetDeliveryService implements PasswordResetDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(ConfigurablePasswordResetDeliveryService.class);
    private static final String DELIVERY_UNAVAILABLE = "password reset delivery unavailable";
    private static final String WEBHOOK_SECRET_HEADER = "X-Password-Reset-Delivery-Secret";

    private final DeliveryMode deliveryMode;
    private final String smsWebhookUrl;
    private final String emailWebhookUrl;
    private final String webhookSecret;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public ConfigurablePasswordResetDeliveryService(
            @Value("${app.auth.password-reset.delivery-mode:disabled}") String deliveryMode,
            @Value("${app.auth.password-reset.sms-webhook-url:}") String smsWebhookUrl,
            @Value("${app.auth.password-reset.email-webhook-url:}") String emailWebhookUrl,
            @Value("${app.auth.password-reset.webhook-secret:}") String webhookSecret,
            @Value("${app.auth.password-reset.webhook-timeout-millis:3000}") long timeoutMillis,
            ObjectMapper objectMapper) {
        this(
                deliveryMode,
                smsWebhookUrl,
                emailWebhookUrl,
                webhookSecret,
                Duration.ofMillis(timeoutMillis),
                HttpClient.newBuilder()
                        .connectTimeout(normalizedTimeout(Duration.ofMillis(timeoutMillis)))
                        .build(),
                objectMapper);
    }

    ConfigurablePasswordResetDeliveryService(
            String deliveryMode,
            String smsWebhookUrl,
            String emailWebhookUrl,
            String webhookSecret,
            Duration timeout,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.deliveryMode = DeliveryMode.from(deliveryMode);
        this.smsWebhookUrl = normalizeOptional(smsWebhookUrl);
        this.emailWebhookUrl = normalizeOptional(emailWebhookUrl);
        this.webhookSecret = normalizeOptional(webhookSecret);
        this.timeout = normalizedTimeout(timeout);
        this.httpClient = httpClient != null
                ? httpClient
                : HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public void sendSmsOtp(String phone, String code) {
        switch (deliveryMode) {
            case LOGGING -> log.info("Password reset SMS OTP queued for {}", maskPhone(phone));
            case WEBHOOK -> postWebhook("sms", phone, "code", code, smsWebhookUrl);
            case DISABLED -> throw deliveryUnavailable("password reset SMS delivery is disabled");
        }
    }

    @Override
    public void sendEmailToken(String email, String token) {
        switch (deliveryMode) {
            case LOGGING -> log.info("Password reset email token queued for {}", maskEmail(email));
            case WEBHOOK -> postWebhook("email", email, "token", token, emailWebhookUrl);
            case DISABLED -> throw deliveryUnavailable("password reset email delivery is disabled");
        }
    }

    private void postWebhook(String channel, String recipient, String credentialName, String credential, String url) {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(webhookSecret)) {
            throw deliveryUnavailable("password reset delivery webhook is not configured");
        }
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("channel", channel, "recipient", recipient, credentialName, credential));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(WEBHOOK_SECRET_HEADER, webhookSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw deliveryUnavailable("password reset delivery webhook returned " + response.statusCode());
            }
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw deliveryUnavailable("password reset delivery webhook is invalid");
        } catch (IOException e) {
            throw deliveryUnavailable(DELIVERY_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw deliveryUnavailable(DELIVERY_UNAVAILABLE);
        }
    }

    private static BusinessException deliveryUnavailable(String message) {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, message);
    }

    private static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone)) {
            return "unknown";
        }
        String normalized = phone.trim();
        if (normalized.length() < 7) {
            return "***";
        }
        return normalized.substring(0, 3) + "****" + normalized.substring(normalized.length() - 4);
    }

    private static String maskEmail(String email) {
        if (!StringUtils.hasText(email) || !email.contains("@")) {
            return "unknown";
        }
        String normalized = email.trim();
        int at = normalized.indexOf('@');
        String local = normalized.substring(0, at);
        String domain = normalized.substring(at + 1);
        String maskedLocal = local.length() <= 2 ? "**" : local.substring(0, 2) + "***";
        return maskedLocal + "@" + domain;
    }

    private static String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private static Duration normalizedTimeout(Duration timeout) {
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(3) : timeout;
    }

    private enum DeliveryMode {
        DISABLED,
        LOGGING,
        WEBHOOK;

        private static DeliveryMode from(String value) {
            if (!StringUtils.hasText(value)) {
                return DISABLED;
            }
            try {
                return DeliveryMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DISABLED;
            }
        }
    }
}
