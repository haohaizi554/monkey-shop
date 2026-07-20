package com.example.monkey.shared.interfaces.storage;

import com.example.monkey.shared.application.storage.FileService;
import com.example.monkey.shared.application.storage.dto.UploadResponseDto;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.storage.dto.UploadFileRequestDto;
import com.example.monkey.shared.interfaces.storage.dto.UploadRequestDto;
import com.example.monkey.shared.interfaces.web.MultipartUploadFile;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
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
}
