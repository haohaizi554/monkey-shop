package com.example.monkey.infrastructure.storage;

import com.example.monkey.domain.storage.StoredImageReferenceReader;
import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class JpaStoredImageReferenceReader implements StoredImageReferenceReader {

    private final MonkeyRepository monkeyRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    public JpaStoredImageReferenceReader(
            MonkeyRepository monkeyRepository, UserRepository userRepository, OrderRepository orderRepository) {
        this.monkeyRepository = monkeyRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public Set<String> findAllReferencedImagePaths() {
        Set<String> imagePaths = new LinkedHashSet<>();
        addAll(imagePaths, monkeyRepository.findAllImageUrls());
        addAll(imagePaths, userRepository.findAllAvatars());
        addAll(imagePaths, orderRepository.findAllProductImages());
        addAll(imagePaths, orderRepository.findAllBuyerAvatars());
        return imagePaths;
    }

    private static void addAll(Set<String> imagePaths, Collection<String> values) {
        if (values == null) {
            return;
        }
        values.stream().filter(value -> value != null && !value.isBlank()).forEach(imagePaths::add);
    }
}
