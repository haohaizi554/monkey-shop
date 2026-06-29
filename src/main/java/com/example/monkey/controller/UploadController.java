package com.example.monkey.controller;

import com.example.monkey.dto.PresignedGetUrlRequestDto;
import com.example.monkey.dto.PresignedGetUrlResponseDto;
import com.example.monkey.dto.PresignedUploadRequestDto;
import com.example.monkey.dto.PresignedUploadResponseDto;
import com.example.monkey.dto.UploadFileRequestDto;
import com.example.monkey.dto.UploadRequestDto;
import com.example.monkey.dto.UploadResponseDto;
import com.example.monkey.service.FileService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.api.Result;
import com.example.monkey.shared.exception.BusinessException;
import com.example.monkey.shared.web.MultipartUploadFile;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/upload", "/api/v1/uploads"})
public class UploadController {

    private final FileService fileService;

    public UploadController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping
    @PreAuthorize(
            "hasAuthority('UPLOAD_PRODUCT_IMAGE') or (#request.type() == 'avatar' and hasAuthority('UPLOAD_AVATAR'))")
    public Result<UploadResponseDto> upload(@Valid @ModelAttribute UploadRequestDto request) {
        if (!"avatar".equals(request.type()) && !"product".equals(request.type())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "unsupported upload type");
        }
        return Result.success(fileService.uploadFile(MultipartUploadFile.from(request.file()), request.type()));
    }

    @PostMapping("/avatar")
    @PreAuthorize("hasAuthority('UPLOAD_AVATAR')")
    public Result<UploadResponseDto> uploadAvatar(@Valid @ModelAttribute UploadFileRequestDto request) {
        return Result.success(fileService.uploadFile(MultipartUploadFile.from(request.file()), "avatar"));
    }

    @PostMapping("/product")
    @PreAuthorize("hasAuthority('UPLOAD_PRODUCT_IMAGE')")
    public Result<UploadResponseDto> uploadProduct(@Valid @ModelAttribute UploadFileRequestDto request) {
        return Result.success(fileService.uploadFile(MultipartUploadFile.from(request.file()), "product"));
    }

    @PostMapping("/presigned")
    @PreAuthorize(
            "hasAuthority('UPLOAD_PRODUCT_IMAGE') or (#request.type() == 'avatar' and hasAuthority('UPLOAD_AVATAR'))")
    public Result<PresignedUploadResponseDto> createPresignedUpload(
            @Valid @RequestBody PresignedUploadRequestDto request) {
        if (!"avatar".equals(request.type()) && !"product".equals(request.type())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "unsupported upload type");
        }
        return Result.success(fileService.createPresignedUpload(request.type(), request.contentType()));
    }

    @GetMapping("/presigned-get")
    @PreAuthorize("isAuthenticated()")
    public Result<PresignedGetUrlResponseDto> createPresignedGetUrl(
            @Valid @ModelAttribute PresignedGetUrlRequestDto request) {
        return Result.success(fileService.createPresignedGetUrl(request.objectKey()));
    }
}
