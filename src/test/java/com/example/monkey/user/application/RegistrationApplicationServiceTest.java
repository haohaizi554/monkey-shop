package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.storage.FileService;
import com.example.monkey.shared.application.storage.UploadFileContent;
import com.example.monkey.shared.application.storage.dto.UploadResponseDto;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegistrationApplicationServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private FileService fileService;

    private RegistrationApplicationService registrationService;

    @BeforeEach
    void setUp() {
        registrationService = new RegistrationApplicationService(userService, captchaService, fileService);
    }

    @Test
    void registerRejectsInvalidCaptchaBeforeAvatarUploadOrUserCreation() {
        when(captchaService.validate("challenge-id", "bad", "register", "127.0.0.1"))
                .thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> registrationService.register(
                        "alice",
                        "StrongPass1!",
                        "18888888888",
                        "alice@example.com",
                        "challenge-id",
                        "bad",
                        "127.0.0.1",
                        avatar()))
                .withMessage("captcha incorrect")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(fileService, userService);
    }

    @Test
    void registerUploadsAvatarAndCreatesUser() {
        UploadFileContent avatar = avatar();
        when(captchaService.validate("challenge-id", "1234", "register", "127.0.0.1"))
                .thenReturn(true);
        when(fileService.uploadFile(avatar, "avatar")).thenReturn(new UploadResponseDto("/images/avatar.png", false));

        registrationService.register(
                "alice",
                "StrongPass1!",
                "18888888888",
                "alice@example.com",
                "challenge-id",
                "1234",
                "127.0.0.1",
                avatar);

        verify(fileService).uploadFile(avatar, "avatar");
        verify(userService).register("alice", "StrongPass1!", "18888888888", "alice@example.com", "/images/avatar.png");
    }

    @Test
    void registerCreatesUserWithoutAvatarUploadWhenAvatarIsMissing() {
        when(captchaService.validate("challenge-id", "1234", "register", "127.0.0.1"))
                .thenReturn(true);

        registrationService.register(
                "alice", "StrongPass1!", "18888888888", "alice@example.com", "challenge-id", "1234", "127.0.0.1", null);

        verifyNoInteractions(fileService);
        verify(userService).register("alice", "StrongPass1!", "18888888888", "alice@example.com", null);
    }

    @Test
    void registerMapsAvatarUploadFailureToPublicValidationFailure() {
        when(captchaService.validate("challenge-id", "1234", "register", "127.0.0.1"))
                .thenReturn(true);
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "upload failed"))
                .when(fileService)
                .uploadFile(any(UploadFileContent.class), eq("avatar"));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> registrationService.register(
                        "alice",
                        "StrongPass1!",
                        "18888888888",
                        "alice@example.com",
                        "challenge-id",
                        "1234",
                        "127.0.0.1",
                        avatar()))
                .withMessage("avatar save failed")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(userService);
    }

    private static UploadFileContent avatar() {
        return new UploadFileContent() {
            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public long size() {
                return 3;
            }

            @Override
            public InputStream openStream() throws IOException {
                return new ByteArrayInputStream(new byte[] {1, 2, 3});
            }
        };
    }
}
