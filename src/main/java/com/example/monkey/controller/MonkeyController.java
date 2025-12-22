package com.example.monkey.controller;

import com.example.monkey.entity.Monkey;
import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.service.ImageCleanupService; // 引入服务
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/monkeys")
public class MonkeyController {
    @Autowired
    private MonkeyRepository repository;
    @Autowired
    private ImageCleanupService imageCleanupService; // 注入清理服务
    @GetMapping
    public List<Monkey> getAllMonkeys() {
        return repository.findAll();
    }
    @PostMapping("/add")
    public String addMonkey(@RequestBody Monkey monkey, HttpSession session) {
        if (!isAdmin(session)) return "error:无权操作";
        if (monkey.getImageUrl() == null || monkey.getImageUrl().isEmpty()) {
            monkey.setImageUrl("/images/default_product.png");
        }
        repository.save(monkey);
        return "ok";
    }
    @PostMapping("/update")
    public String updateMonkey(@RequestBody Monkey monkey, HttpSession session) {
        if (!isAdmin(session)) return "error:无权操作";
        // 1. 查出旧数据
        Monkey oldMonkey = repository.findById(monkey.getId()).orElse(null);
        if (oldMonkey == null) return "error:商品不存在";
        String oldImage = oldMonkey.getImageUrl();
        // 2. 保存新数据
        repository.save(monkey);
        // 3. 尝试清理旧图片 (如果图片变了)
        if (oldImage != null && !oldImage.equals(monkey.getImageUrl())) {
            imageCleanupService.tryDelete(oldImage);
        }
        return "ok";
    }
    @DeleteMapping("/{id}")
    public String deleteMonkey(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return "error:无权操作";
        Monkey monkey = repository.findById(id).orElse(null);
        if (monkey != null) {
            String imageToDelete = monkey.getImageUrl();
            // 1. 先删数据库记录
            repository.deleteById(id);
            // 2. 再尝试删文件 (此时数据库里已经没有这条记录了，计数器会减1)
            imageCleanupService.tryDelete(imageToDelete);
            return "ok";
        }
        return "error:商品不存在";
    }
    private boolean isAdmin(HttpSession session) {
        return "ADMIN".equals(session.getAttribute("IDENTITY"));
    }
}