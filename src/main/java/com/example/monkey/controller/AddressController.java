package com.example.monkey.controller;

import com.example.monkey.entity.Address;
import com.example.monkey.repository.AddressRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/address")
public class AddressController {

    @Autowired
    private AddressRepository addressRepository;

    // 1. 获取我的地址列表
    @GetMapping
    public List<Address> myAddresses(HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        if (userId == null) return null;
        return addressRepository.findByUserId(userId);
    }

    // 2. 新增地址
    @PostMapping
    public String addAddress(@RequestBody Address address, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        if (userId == null) return "请先登录";

        address.setUserId(userId);

        // 如果是第一条地址，自动设为默认
        List<Address> list = addressRepository.findByUserId(userId);
        if (list.isEmpty()) {
            address.setIsDefault(1);
        } else {
            address.setIsDefault(0);
        }

        addressRepository.save(address);
        return "ok";
    }

    // 3. 设为默认
    @PostMapping("/set-default/{id}")
    public String setDefault(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        if (userId == null) return "error";

        addressRepository.clearDefault(userId);
        Address address = addressRepository.findById(id).orElse(null);
        if (address != null && address.getUserId().equals(userId)) {
            address.setIsDefault(1);
            addressRepository.save(address);
        }
        return "ok";
    }

    // 4. 删除地址
    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        Address address = addressRepository.findById(id).orElse(null);
        if (address != null && address.getUserId().equals(userId)) {
            addressRepository.delete(address);
            return "ok";
        }
        return "error";
    }
}