package com.example.monkey.infrastructure.storage;

import com.example.monkey.domain.storage.ImageUsageChecker;
import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import org.springframework.stereotype.Component;

@Component
public class JpaImageUsageChecker implements ImageUsageChecker {

    private final MonkeyRepository monkeyRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    public JpaImageUsageChecker(
            MonkeyRepository monkeyRepository, UserRepository userRepository, OrderRepository orderRepository) {
        this.monkeyRepository = monkeyRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public boolean isUsed(String imagePath) {
        return monkeyRepository.countByImageUrl(imagePath) > 0
                || userRepository.countByAvatar(imagePath) > 0
                || orderRepository.countByProductImage(imagePath) > 0
                || orderRepository.countByBuyerAvatar(imagePath) > 0;
    }
}
