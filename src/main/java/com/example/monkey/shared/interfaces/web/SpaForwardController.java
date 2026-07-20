package com.example.monkey.shared.interfaces.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {

    @GetMapping({
        "/",
        "/login",
        "/shop",
        "/search",
        "/recommendations",
        "/cart",
        "/checkout",
        "/orders",
        "/payment",
        "/logistics",
        "/membership",
        "/profile",
        "/admin",
        "/admin/orders",
        "/admin/returns",
        "/admin/payments",
        "/admin/logistics",
        "/admin/members",
        "/inventory",
        "/marketing",
        "/risk",
        "/dashboard",
        "/tenants"
    })
    public String forwardSpaRoute() {
        return "forward:/index.html";
    }

    @GetMapping({"/shop/{productId}", "/payment/{orderId}", "/logistics/{orderId}", "/orders/{orderId}/review"})
    public String forwardSpaDetailRoute() {
        return "forward:/index.html";
    }

    @GetMapping("/favicon.ico")
    public String legacyFavicon() {
        return "redirect:/favicon.svg";
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
