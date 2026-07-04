package com.example.monkey.search.application;

import com.example.monkey.search.application.dto.HotKeywordDto;
import com.example.monkey.search.application.dto.RecommendationDto;
import com.example.monkey.search.application.dto.SearchPageDto;
import com.example.monkey.search.application.dto.SearchProductDto;
import com.example.monkey.search.application.dto.SearchSuggestionDto;
import com.example.monkey.search.application.dto.UserSearchProfileDto;
import com.example.monkey.search.domain.HotKeyword;
import com.example.monkey.search.domain.RecommendationItem;
import com.example.monkey.search.domain.SearchPage;
import com.example.monkey.search.domain.SearchProduct;
import com.example.monkey.search.domain.SearchSuggestion;
import com.example.monkey.search.domain.UserSearchProfile;

public final class SearchDtoAssembler {

    private SearchDtoAssembler() {}

    public static SearchPageDto toPage(SearchPage page) {
        return new SearchPageDto(
                page.content().stream().map(SearchDtoAssembler::toProduct).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.first(),
                page.last());
    }

    public static SearchProductDto toProduct(SearchProduct product) {
        return new SearchProductDto(
                product.productId(),
                product.categoryId(),
                product.name(),
                product.title(),
                product.imageUrl(),
                product.originalPrice(),
                product.memberPrice(),
                product.attributes(),
                product.score());
    }

    public static SearchSuggestionDto toSuggestion(SearchSuggestion suggestion) {
        return new SearchSuggestionDto(suggestion.keyword(), suggestion.source(), suggestion.score());
    }

    public static HotKeywordDto toHotKeyword(HotKeyword keyword) {
        return new HotKeywordDto(keyword.keyword(), keyword.score());
    }

    public static RecommendationDto toRecommendation(RecommendationItem item) {
        return new RecommendationDto(
                item.productId(), item.name(), item.title(), item.imageUrl(), item.reason(), item.score());
    }

    public static UserSearchProfileDto toProfile(UserSearchProfile profile) {
        return new UserSearchProfileDto(
                profile.userId(),
                mask(profile.interestProfile()),
                profile.tags(),
                profile.updatedAt(),
                profile.version());
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 2) {
            return "*".repeat(trimmed.length());
        }
        return trimmed.charAt(0) + "***" + trimmed.charAt(trimmed.length() - 1);
    }
}
