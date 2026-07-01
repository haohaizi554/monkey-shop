package com.example.monkey.user.domain;

import java.util.List;
import java.util.Optional;

public interface AddressBook {

    AddressPage findByUserId(Long userId, AddressPageRequest request);

    boolean existsForUser(Long userId);

    Optional<AddressRecord> findByIdAndUserId(Long id, Long userId);

    AddressRecord save(AddressRecord address);

    void clearDefault(Long userId);

    void deleteById(Long id);

    record AddressRecord(
            Long id, Long userId, String receiverName, String phone, String detailAddress, Integer isDefault) {}

    record AddressPageRequest(int page, int size, List<SortOrder> sortOrders) {
        public AddressPageRequest {
            sortOrders = sortOrders == null ? List.of() : List.copyOf(sortOrders);
        }
    }

    record AddressPage(
            List<AddressRecord> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {
        public AddressPage {
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    record SortOrder(String property, Direction direction) {
        public enum Direction {
            ASC,
            DESC
        }
    }
}
