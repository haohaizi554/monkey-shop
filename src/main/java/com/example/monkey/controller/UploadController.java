package com.example.monkey.controller;

import com.example.monkey.service.FileService;
import jakarta.servlet.http.HttpSession;
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
            @RequestParam("file") MultipartFile file, @RequestParam("type") String type, HttpSession session) {
        String identity = (String) session.getAttribute("IDENTITY");
        if ("product".equals(type) && !"ADMIN".equals(identity)) {
            return "error:forbidden";
        }
        if ("avatar".equals(type) && identity == null) {
            return "error:login required";
        }
        return fileService.uploadFile(file, type);
    }
}
