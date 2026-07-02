package com.example.monkey.shared.interfaces.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/shop", "/orders", "/profile", "/admin"})
    public String forwardSpaRoute() {
        return "forward:/index.html";
    }

    @GetMapping("/shop.html")
    public String legacyShop() {
        return "redirect:/shop";
    }

    @GetMapping("/orders.html")
    public String legacyOrders() {
        return "redirect:/orders";
    }

    @GetMapping("/profile.html")
    public String legacyProfile() {
        return "redirect:/profile";
    }

    @GetMapping("/admin.html")
    public String legacyAdmin() {
        return "redirect:/admin";
    }
}
