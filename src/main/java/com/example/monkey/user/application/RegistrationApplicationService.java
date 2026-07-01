package com.example.monkey.user.application;

import com.example.monkey.shared.application.storage.FileService;
import com.example.monkey.shared.application.storage.UploadFileContent;
import com.example.monkey.shared.application.storage.dto.UploadResponseDto;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class RegistrationApplicationService {
    static final String ACTION_REGISTER = "register";
    static final String REGISTRATION_CAPTCHA_INVALID = "captcha incorrect";
    static final String REGISTRATION_AVATAR_FAILED = "avatar save failed";

    private final UserService userService;
    private final CaptchaService captchaService;
    private final FileService fileService;

    public RegistrationApplicationService(
            UserService userService, CaptchaService captchaService, FileService fileService) {
        this.userService = userService;
        this.captchaService = captchaService;
        this.fileService = fileService;
    }

    public void register(
            String username,
            String password,
            String phone,
            String email,
            String captchaChallengeId,
            String captcha,
            String clientIp,
            UploadFileContent avatarFile) {
        if (!captchaService.validate(captchaChallengeId, captcha, ACTION_REGISTER, clientIp)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, REGISTRATION_CAPTCHA_INVALID);
        }

        String avatarPath = null;
        if (avatarFile != null && !avatarFile.isEmpty()) {
            avatarPath = uploadAvatar(avatarFile);
        }
        userService.register(username, password, phone, email, avatarPath);
    }

    private String uploadAvatar(UploadFileContent avatarFile) {
        try {
            UploadResponseDto uploadResult = fileService.uploadFile(avatarFile, "avatar");
            return uploadResult.path();
        } catch (BusinessException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, REGISTRATION_AVATAR_FAILED);
        }
    }
}
