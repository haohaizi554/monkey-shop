package com.example.monkey.search.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "search_history")
public class SearchHistoryEntity {

    @Id
    private Long id;

    private Long userId;

    @Column(length = 128)
    private String keyword;

    @Column(length = 128)
    private String normalizedKeyword;

    private Long categoryId;

    @Column(columnDefinition = "json")
    private String filtersJson;

    private Long clickedProductId;

    @Column(nullable = false)
    private boolean converted;

    @Column(nullable = false)
    private int resultCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public SearchHistoryEntity() {}

    public SearchHistoryEntity(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getNormalizedKeyword() {
        return normalizedKeyword;
    }

    public void setNormalizedKeyword(String normalizedKeyword) {
        this.normalizedKeyword = normalizedKeyword;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getFiltersJson() {
        return filtersJson;
    }

    public void setFiltersJson(String filtersJson) {
        this.filtersJson = filtersJson;
    }

    public Long getClickedProductId() {
        return clickedProductId;
    }

    public void setClickedProductId(Long clickedProductId) {
        this.clickedProductId = clickedProductId;
    }

    public boolean isConverted() {
        return converted;
    }

    public void setConverted(boolean converted) {
        this.converted = converted;
    }

    public int getResultCount() {
        return resultCount;
    }

    public void setResultCount(int resultCount) {
        this.resultCount = resultCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
