package com.example.monkey.search.interfaces;

import com.example.monkey.search.application.SearchApplicationService;
import com.example.monkey.search.application.dto.HotKeywordDto;
import com.example.monkey.search.application.dto.RecommendationDto;
import com.example.monkey.search.application.dto.SearchConversionRequestDto;
import com.example.monkey.search.application.dto.SearchPageDto;
import com.example.monkey.search.application.dto.SearchProductQueryDto;
import com.example.monkey.search.application.dto.SearchSuggestionDto;
import com.example.monkey.search.application.dto.UserSearchProfileDto;
import com.example.monkey.search.application.dto.UserSearchProfileRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/search", "/api/v1/search"})
public class SearchController {

    private final SearchApplicationService searchApplicationService;

    public SearchController(SearchApplicationService searchApplicationService) {
        this.searchApplicationService = searchApplicationService;
    }

    @GetMapping("/products")
    @PreAuthorize("permitAll()")
    public Result<SearchPageDto> products(
            @Valid @ModelAttribute SearchProductQueryDto request, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(searchApplicationService.search(request, currentUser));
    }

    @GetMapping("/suggestions")
    @PreAuthorize("permitAll()")
    public Result<List<SearchSuggestionDto>> suggestions(
            @RequestParam String keyword, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(searchApplicationService.suggestions(keyword, currentUser));
    }

    @GetMapping("/hot")
    @PreAuthorize("permitAll()")
    public Result<List<HotKeywordDto>> hotKeywords() {
        return Result.success(searchApplicationService.hotKeywords());
    }

    @GetMapping("/recommendations")
    @PreAuthorize("hasAuthority('SEARCH_READ')")
    public Result<List<RecommendationDto>> recommendations(@AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(searchApplicationService.recommendations(currentUser));
    }

    @PostMapping("/profile")
    @PreAuthorize("hasAuthority('SEARCH_WRITE')")
    public Result<UserSearchProfileDto> upsertProfile(
            @Valid @RequestBody UserSearchProfileRequestDto request, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(searchApplicationService.upsertProfile(currentUser, request));
    }

    @PostMapping("/conversions")
    @PreAuthorize("hasAuthority('SEARCH_WRITE')")
    public Result<Void> recordConversion(
            @Valid @RequestBody SearchConversionRequestDto request, @AuthenticationPrincipal SessionUser currentUser) {
        searchApplicationService.recordConversion(currentUser, request);
        return Result.success();
    }
}
