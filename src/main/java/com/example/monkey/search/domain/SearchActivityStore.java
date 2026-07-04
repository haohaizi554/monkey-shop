package com.example.monkey.search.domain;

import java.time.Duration;
import java.util.List;

public interface SearchActivityStore {

    void recordKeyword(String keyword);

    List<HotKeyword> hotKeywords(int limit);

    List<SearchSuggestion> suggestions(String prefix, int limit);

    void cacheSuggestions(String prefix, List<SearchSuggestion> suggestions, Duration ttl);

    void refreshHotKeywordSnapshot();
}
