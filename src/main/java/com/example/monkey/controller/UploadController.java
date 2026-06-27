package com.example.monkey.controller;

import com.example.monkey.security.SessionUser;
import com.example.monkey.service.FileService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final FileService fileService;

    public UploadController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping
    public String upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type,
            @AuthenticationPrincipal SessionUser currentUser) {
        if ("product".equals(type) && (currentUser == null || !currentUser.isAdmin())) {
            return "error:forbidden";
        }
        if ("avatar".equals(type) && currentUser == null) {
            return "error:login required";
        }
        return fileService.uploadFile(file, type);
    }
}
