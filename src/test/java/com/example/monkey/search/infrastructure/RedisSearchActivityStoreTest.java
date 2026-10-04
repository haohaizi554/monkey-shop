package com.example.monkey.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.search.domain.SearchSuggestion;
import com.example.monkey.shared.application.tenant.TenantContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;

class RedisSearchActivityStoreTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void fallbackRecordsHotKeywordsAndCachedSuggestions() {
        RedisSearchActivityStore store = new RedisSearchActivityStore(noRedis());

        store.recordKeyword("Phone");
        store.recordKeyword("phone");
        store.cacheSuggestions("phone", List.of(new SearchSuggestion("Phone Pro", "product", 9)), Duration.ofHours(1));
        store.refreshHotKeywordSnapshot();

        assertThat(store.hotKeywords(10)).extracting("keyword").containsExactly("phone");
        assertThat(store.hotKeywords(10).get(0).score()).isEqualTo(2);
        assertThat(store.suggestions("phone", 10))
                .extracting(SearchSuggestion::keyword)
                .containsExactly("Phone Pro");
    }

    @Test
    void fallbackHotKeywordsAndSuggestionsRemainIsolatedByTenant() {
        RedisSearchActivityStore store = new RedisSearchActivityStore(noRedis());

        TenantContext.setTenantId(1L);
        store.recordKeyword("same-key");
        store.cacheSuggestions(
                "same-key", List.of(new SearchSuggestion("tenant-one", "product", 1)), Duration.ofHours(1));

        TenantContext.setTenantId(2L);
        store.recordKeyword("same-key");
        store.cacheSuggestions(
                "same-key", List.of(new SearchSuggestion("tenant-two", "product", 2)), Duration.ofHours(1));

        TenantContext.setTenantId(1L);
        assertThat(store.hotKeywords(10)).extracting("keyword").containsExactly("same-key");
        assertThat(store.hotKeywords(10).get(0).score()).isEqualTo(1);
        assertThat(store.suggestions("same-key", 10))
                .extracting(SearchSuggestion::keyword)
                .containsExactly("tenant-one");

        TenantContext.setTenantId(2L);
        assertThat(store.hotKeywords(10)).extracting("keyword").containsExactly("same-key");
        assertThat(store.hotKeywords(10).get(0).score()).isEqualTo(1);
        assertThat(store.suggestions("same-key", 10))
                .extracting(SearchSuggestion::keyword)
                .containsExactly("tenant-two");
    }

    private static ObjectProvider<StringRedisTemplate> noRedis() {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject(@NonNull Object... args) {
                throw new NoSuchBeanDefinitionException(StringRedisTemplate.class);
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return null;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return null;
            }

            @Override
            public StringRedisTemplate getObject() {
                throw new NoSuchBeanDefinitionException(StringRedisTemplate.class);
            }
        };
    }
}
