package com.example.monkey.controller;

import com.example.monkey.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
public class UploadController {
    @Autowired
    private FileService fileService;
    @PostMapping
    public String upload(@RequestParam("file") MultipartFile file, @RequestParam("type") String type) {
        return fileService.uploadFile(file, type);
    }
}