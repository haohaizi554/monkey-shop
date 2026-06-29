package com.example.monkey.domain.user;

public interface PasswordResetDeliveryService {

    void sendSmsOtp(String phone, String code);

    void sendEmailToken(String email, String token);

    static PasswordResetDeliveryService noop() {
        return new PasswordResetDeliveryService() {
            @Override
            public void sendSmsOtp(String phone, String code) {
                // No-op for unit tests and local construction.
            }

            @Override
            public void sendEmailToken(String email, String token) {
                // No-op for unit tests and local construction.
            }
        };
    }
}
