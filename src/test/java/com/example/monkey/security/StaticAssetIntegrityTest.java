package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class StaticAssetIntegrityTest {

    private static final List<Path> LEGACY_PAGES = List.of(
            Path.of("src/main/resources/static/index.html"),
            Path.of("src/main/resources/static/shop.html"),
            Path.of("src/main/resources/static/orders.html"),
            Path.of("src/main/resources/static/profile.html"),
            Path.of("src/main/resources/static/admin.html"));

    private static final Pattern EXTERNAL_ASSET_TAG = Pattern.compile(
            "<(?:script|link)[^>]+(?:src|href)=\"https://[^\"]+\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_SCRIPT_TAG = Pattern.compile(
            "<script(?![^>]+\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_EVENT_HANDLER = Pattern.compile(
            "\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_STYLE_BLOCK = Pattern.compile(
            "<style\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_STYLE_ATTRIBUTE = Pattern.compile(
            "\\sstyle\\s*=", Pattern.CASE_INSENSITIVE);

    @Test
    void legacyPagesDoNotUseFloatingOrUnapprovedCdns() throws IOException {
        for (Path page : LEGACY_PAGES) {
            String html = Files.readString(page, StandardCharsets.UTF_8);

            assertThat(html)
                    .as(page + " should not use floating Vue CDN URLs")
                    .doesNotContain("unpkg.com")
                    .doesNotContain("vue@3/dist/vue.global.js")
                    .doesNotContain("fonts.googleapis.com");
        }
    }

    @Test
    void legacyPagesDoNotUseInlineScriptsOrEventHandlers() throws IOException {
        for (Path page : LEGACY_PAGES) {
            String html = Files.readString(page, StandardCharsets.UTF_8);

            assertThat(INLINE_SCRIPT_TAG.matcher(html).find())
                    .as(page + " should not use inline script blocks")
                    .isFalse();
            assertThat(INLINE_EVENT_HANDLER.matcher(html).find())
                    .as(page + " should not use inline event handler attributes")
                    .isFalse();
        }
    }

    @Test
    void legacyPagesDoNotUseInlineStyles() throws IOException {
        for (Path page : LEGACY_PAGES) {
            String html = Files.readString(page, StandardCharsets.UTF_8);

            assertThat(INLINE_STYLE_BLOCK.matcher(html).find())
                    .as(page + " should not use inline style blocks")
                    .isFalse();
            assertThat(INLINE_STYLE_ATTRIBUTE.matcher(html).find())
                    .as(page + " should not use inline style attributes")
                    .isFalse();
        }
    }

    @Test
    void allExternalScriptAndStylesheetTagsUseSri() throws IOException {
        for (Path page : LEGACY_PAGES) {
            String html = Files.readString(page, StandardCharsets.UTF_8);
            Matcher matcher = EXTERNAL_ASSET_TAG.matcher(html);
            while (matcher.find()) {
                String tag = matcher.group();
                assertThat(tag)
                        .as(page + " external asset should include SRI: " + tag)
                        .contains("integrity=\"sha384-")
                        .contains("crossorigin=\"anonymous\"");
            }
        }
    }
}
