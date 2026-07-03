package com.example.monkey.product.domain;

import java.util.List;

public record CategoryNode(Long id, Long parentId, int level, String code, String name, List<CategoryNode> children) {
    public CategoryNode {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public boolean isLeafLevel() {
        return level == 3;
    }
}
