package com.example.monkey.service;

import com.example.monkey.entity.Address;
import com.example.monkey.entity.Monkey;
import com.example.monkey.entity.Order;
import com.example.monkey.entity.User;
import com.example.monkey.repository.AddressRepository;
import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Service
public class OrderService {

    @Autowired private OrderRepository orderRepository;
    @Autowired private MonkeyRepository monkeyRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ImageCleanupService imageCleanupService;

    // 1. 创建订单 (核心业务)
    @Transactional
    public String createOrder(Long userId, Long monkeyId, Long addressId) {
        Monkey monkey = monkeyRepository.findById(monkeyId).orElse(null);
        Address address = addressRepository.findById(addressId).orElse(null);
        User user = userRepository.findById(userId).orElse(null);

        if (monkey == null) return "error:商品不存在";
        if (address == null) return "error:地址不存在";
        if (user == null) return "error:用户异常";
        if (!address.getUserId().equals(userId)) return "error:地址非法";

        // 原子扣减库存
        int rows = monkeyRepository.deductStock(monkeyId);
        if (rows == 0) return "error:手慢了，库存不足！";

        // 组装订单快照
        Order order = new Order();
        order.setUserId(userId);
        String orderNo = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + (int)(Math.random()*1000);
        order.setOrderNo(orderNo);

        order.setBuyerName(user.getUsername());
        order.setBuyerAvatar(user.getAvatar());

        order.setProductName(monkey.getName());
        order.setProductImage(monkey.getImageUrl());
        order.setPrice(monkey.getPrice());
        order.setDescription(monkey.getDescription());

        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getPhone());
        order.setAddressSnapshot(address.getDetailAddress());

        order.setStatus("已支付");
        order.setShippingTime(null);

        orderRepository.save(order);
        return "ok";
    }
    // 2. 发货
    public String shipOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null) {
            order.setStatus("已发货");
            order.setShippingTime(java.time.LocalDateTime.now());
            orderRepository.save(order);
            return "ok";
        }
        return "error:订单不存在";
    }
    // 3. 确认收货
    public String receiveOrder(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null && order.getUserId().equals(userId) && "已发货".equals(order.getStatus())) {
            order.setStatus("已完成");
            orderRepository.save(order);
            return "ok";
        }
        return "error:操作失败";
    }
    // 4. 删除订单 (含库存回滚 + 图片清理)
    @Transactional
    public String deleteOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null) {
            String pImg = order.getProductImage();
            String bImg = order.getBuyerAvatar();
            // 如果订单未完成/未退款，删除时回滚库存
            if (!"已完成".equals(order.getStatus()) && !"已退款".equals(order.getStatus())) {
                restoreStockByName(order.getProductName());
            }
            orderRepository.deleteById(orderId);
            // 尝试清理图片
            imageCleanupService.tryDelete(pImg);
            imageCleanupService.tryDelete(bImg);
            return "ok";
        }
        return "error:订单不存在";
    }
    // 5. 状态流转 (申请退货/发货/确认退货)
    public String updateStatus(Long orderId, String targetStatus, String expectedCurrentStatus) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null && expectedCurrentStatus.equals(order.getStatus())) {
            order.setStatus(targetStatus);
            orderRepository.save(order);
            return "ok";
        }
        return "error:状态不对";
    }
    // 6. 确认退货 (退款 + 回滚库存)
    @Transactional
    public String confirmReturn(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null && "退货中".equals(order.getStatus())) {
            order.setStatus("已退款");
            orderRepository.save(order);
            restoreStockByName(order.getProductName());
            return "ok";
        }
        return "error:状态不对";
    }
    // 辅助：根据名字恢复库存
    private void restoreStockByName(String productName) {
        List<Monkey> monkeys = monkeyRepository.findAll();
        for (Monkey m : monkeys) {
            if (m.getName().equals(productName)) {
                monkeyRepository.restoreStock(m.getId());
                break;
            }
        }
    }
}