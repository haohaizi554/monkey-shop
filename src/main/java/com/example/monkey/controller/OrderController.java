package com.example.monkey.controller;

import com.example.monkey.entity.Order;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.service.OrderService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @PostMapping("/create")
    public String createOrder(@RequestBody Map<String, Long> params, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        if (userId == null) return "error:请先登录";
        return orderService.createOrder(userId, params.get("monkeyId"), params.get("addressId"));
    }
    @GetMapping("/my")
    public List<Order> myOrders(HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        if (userId == null) return null;
        return orderRepository.findByUserIdOrderByCreateTimeDesc(userId);
    }
    @GetMapping("/all")
    public List<Order> getAllOrders(HttpSession session) {
        if (!isAdmin(session)) return null;
        return orderRepository.findAll(Sort.by(Sort.Direction.DESC, "createTime"));
    }
    @PostMapping("/ship/{id}")
    public String shipOrder(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return "error:无权操作";
        return orderService.shipOrder(id);
    }
    @PostMapping("/receive/{id}")
    public String receiveOrder(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        if (userId == null) return "error:请先登录";
        return orderService.receiveOrder(id, userId);
    }
    @DeleteMapping("/{id}")
    public String deleteOrder(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return "error:无权操作";
        return orderService.deleteOrder(id);
    }
    // --- 退货流程 ---
    @PostMapping("/return/apply/{id}")
    public String applyReturn(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        // 校验是否是本人操作逻辑在 Service 内部或这里校验均可，Service 里有校验
        return orderService.updateStatus(id, "申请退货", "已完成");
    }
    @PostMapping("/return/approve/{id}")
    public String approveReturn(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return "error:无权操作";
        return orderService.updateStatus(id, "待退货发货", "申请退货");
    }
    @PostMapping("/return/ship/{id}")
    public String userShipReturn(@PathVariable Long id, HttpSession session) {
        return orderService.updateStatus(id, "退货中", "待退货发货");
    }
    @PostMapping("/return/confirm/{id}")
    public String confirmReturn(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return "error:无权操作";
        return orderService.confirmReturn(id);
    }
    private boolean isAdmin(HttpSession session) {
        return "ADMIN".equals(session.getAttribute("IDENTITY"));
    }
}