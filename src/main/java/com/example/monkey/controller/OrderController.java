package com.example.monkey.controller;

import com.example.monkey.entity.Address;
import com.example.monkey.entity.Monkey;
import com.example.monkey.entity.Order;
import com.example.monkey.entity.User;
import com.example.monkey.repository.AddressRepository;
import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import com.example.monkey.service.ImageCleanupService; // 引入服务
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private MonkeyRepository monkeyRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ImageCleanupService imageCleanupService; // 注入

    @Transactional
    @PostMapping("/create")
    public String createOrder(@RequestBody Map<String, Long> params, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        if (userId == null) return "error:请先登录";

        Long monkeyId = params.get("monkeyId");
        Long addressId = params.get("addressId");

        Monkey monkey = monkeyRepository.findById(monkeyId).orElse(null);
        Address address = addressRepository.findById(addressId).orElse(null);
        User user = userRepository.findById(userId).orElse(null);

        if (monkey == null || address == null || user == null) return "error:数据异常";
        if (!address.getUserId().equals(userId)) return "error:地址非法";

        if (monkey.getStock() == null || monkey.getStock() <= 0) {
            return "error:手慢了，库存不足！";
        }
        monkey.setStock(monkey.getStock() - 1);
        monkeyRepository.save(monkey);

        Order order = new Order();
        order.setUserId(userId);
        String orderNo = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + (int)(Math.random()*1000);
        order.setOrderNo(orderNo);

        order.setBuyerName(user.getUsername());
        order.setBuyerAvatar(user.getAvatar()); // 引用 +1

        order.setProductName(monkey.getName());
        order.setProductImage(monkey.getImageUrl()); // 引用 +1
        order.setPrice(monkey.getPrice());
        order.setDescription(monkey.getDescription());

        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getPhone());
        order.setAddressSnapshot(address.getDetailAddress());

        order.setStatus("已支付");
        orderRepository.save(order);
        return "ok";
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
        Order order = orderRepository.findById(id).orElse(null);
        if (order != null) {
            order.setStatus("已发货");
            order.setShippingTime(java.time.LocalDateTime.now());
            orderRepository.save(order);
            return "ok";
        }
        return "error:订单不存在";
    }

    @PostMapping("/receive/{id}")
    public String receiveOrder(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        Order order = orderRepository.findById(id).orElse(null);
        if (order != null && order.getUserId().equals(userId) && "已发货".equals(order.getStatus())) {
            order.setStatus("已完成");
            orderRepository.save(order);
            return "ok";
        }
        return "error:操作失败";
    }

    @Transactional
    @DeleteMapping("/{id}")
    public String deleteOrder(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return "error:无权操作";

        Order order = orderRepository.findById(id).orElse(null);
        if (order != null) {
            // 记录要尝试删除的图片
            String productImg = order.getProductImage();
            String buyerImg = order.getBuyerAvatar();

            // 库存回滚逻辑
            if (!"已完成".equals(order.getStatus()) && !"已退款".equals(order.getStatus())) {
                restoreStock(order.getProductName());
            }

            // 1. 删除订单 (数据库记录消失，引用计数减少)
            orderRepository.deleteById(id);

            // 2. 尝试清理图片
            imageCleanupService.tryDelete(productImg);
            imageCleanupService.tryDelete(buyerImg);

            return "ok";
        }
        return "error:订单不存在";
    }

    @PostMapping("/return/apply/{id}")
    public String applyReturn(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        Order order = orderRepository.findById(id).orElse(null);
        if (order != null && order.getUserId().equals(userId) && "已完成".equals(order.getStatus())) {
            order.setStatus("申请退货");
            orderRepository.save(order);
            return "ok";
        }
        return "error:无法申请";
    }

    @PostMapping("/return/approve/{id}")
    public String approveReturn(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return "error:无权操作";
        Order order = orderRepository.findById(id).orElse(null);
        if (order != null && "申请退货".equals(order.getStatus())) {
            order.setStatus("待退货发货");
            orderRepository.save(order);
            return "ok";
        }
        return "error:状态不对";
    }

    @PostMapping("/return/ship/{id}")
    public String userShipReturn(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        Order order = orderRepository.findById(id).orElse(null);
        if (order != null && order.getUserId().equals(userId) && "待退货发货".equals(order.getStatus())) {
            order.setStatus("退货中");
            orderRepository.save(order);
            return "ok";
        }
        return "error:状态不对";
    }

    @Transactional
    @PostMapping("/return/confirm/{id}")
    public String confirmReturn(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return "error:无权操作";
        Order order = orderRepository.findById(id).orElse(null);
        if (order != null && "退货中".equals(order.getStatus())) {
            order.setStatus("已退款");
            orderRepository.save(order);
            restoreStock(order.getProductName());
            return "ok";
        }
        return "error:状态不对";
    }

    private void restoreStock(String productName) {
        List<Monkey> monkeys = monkeyRepository.findAll();
        for (Monkey m : monkeys) {
            if (m.getName().equals(productName)) {
                m.setStock(m.getStock() + 1);
                monkeyRepository.save(m);
                break;
            }
        }
    }
    private boolean isAdmin(HttpSession session) {
        return "ADMIN".equals(session.getAttribute("IDENTITY"));
    }
}