package com.example.monkey.controller;

import com.example.monkey.entity.Order;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.security.SessionUser;
import com.example.monkey.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @PostMapping("/create")
    public String createOrder(@RequestBody Map<String, Long> params, @AuthenticationPrincipal SessionUser currentUser) {
        Long userId = userId(currentUser);
        if (userId == null) return "error:请先登录";
        return orderService.createOrder(userId, params.get("monkeyId"), params.get("addressId"));
    }
    @GetMapping("/my")
    public List<Order> myOrders(@AuthenticationPrincipal SessionUser currentUser) {
        Long userId = userId(currentUser);
        if (userId == null) return null;
        return orderRepository.findByUserIdOrderByCreateTimeDesc(userId);
    }
    @GetMapping("/all")
    public List<Order> getAllOrders(@AuthenticationPrincipal SessionUser currentUser) {
        if (!isAdmin(currentUser)) return null;
        return orderRepository.findAll(Sort.by(Sort.Direction.DESC, "createTime"));
    }
    @PostMapping("/ship/{id}")
    public String shipOrder(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        if (!isAdmin(currentUser)) return "error:无权操作";
        return orderService.shipOrder(id);
    }
    @PostMapping("/receive/{id}")
    public String receiveOrder(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        Long userId = userId(currentUser);
        if (userId == null) return "error:请先登录";
        return orderService.receiveOrder(id, userId);
    }
    @DeleteMapping("/{id}")
    public String deleteOrder(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        if (!isAdmin(currentUser)) return "error:无权操作";
        return orderService.deleteOrder(id);
    }
    // --- 退货流程 ---
    @PostMapping("/return/apply/{id}")
    public String applyReturn(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        Long userId = userId(currentUser);
        if (userId == null) return "error:请先登录";
        return orderService.updateStatusForOwner(id, userId, "申请退货", "已完成");
    }
    @PostMapping("/return/approve/{id}")
    public String approveReturn(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        if (!isAdmin(currentUser)) return "error:无权操作";
        return orderService.updateStatus(id, "待退货发货", "申请退货");
    }
    @PostMapping("/return/ship/{id}")
    public String userShipReturn(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        Long userId = userId(currentUser);
        if (userId == null) return "error:请先登录";
        return orderService.updateStatusForOwner(id, userId, "退货中", "待退货发货");
    }
    @PostMapping("/return/confirm/{id}")
    public String confirmReturn(@PathVariable Long id, @AuthenticationPrincipal SessionUser currentUser) {
        if (!isAdmin(currentUser)) return "error:无权操作";
        return orderService.confirmReturn(id);
    }
    private boolean isAdmin(SessionUser currentUser) {
        return currentUser != null && currentUser.isAdmin();
    }

    private static Long userId(SessionUser currentUser) {
        return currentUser == null ? null : currentUser.id();
    }
}
