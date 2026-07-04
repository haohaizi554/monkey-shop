package com.example.monkey.logistics.infrastructure;

import com.example.monkey.logistics.domain.AddressParser;
import com.example.monkey.logistics.domain.ParsedAddress;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.logistics.address-parser", havingValue = "rule", matchIfMissing = true)
public class RuleBasedAddressParser implements AddressParser {

    private static final Map<String, String> CORRECTIONS = new LinkedHashMap<>();
    private static final Map<String, String[]> KNOWN_REGIONS = new LinkedHashMap<>();

    static {
        CORRECTIONS.put("Hangzou", "Hangzhou");
        CORRECTIONS.put("Shanghi", "Shanghai");
        CORRECTIONS.put("Beijin", "Beijing");
        CORRECTIONS.put("杭洲", "杭州");
        CORRECTIONS.put("上诲", "上海");
        CORRECTIONS.put("北经", "北京");

        KNOWN_REGIONS.put("Zhejiang", new String[] {"Zhejiang", "Hangzhou", "Xihu"});
        KNOWN_REGIONS.put("Shanghai", new String[] {"Shanghai", "Shanghai", "Pudong"});
        KNOWN_REGIONS.put("Beijing", new String[] {"Beijing", "Beijing", "Chaoyang"});
        KNOWN_REGIONS.put("浙江", new String[] {"浙江省", "杭州市", "西湖区"});
        KNOWN_REGIONS.put("上海", new String[] {"上海市", "上海市", "浦东新区"});
        KNOWN_REGIONS.put("北京", new String[] {"北京市", "北京市", "朝阳区"});
        KNOWN_REGIONS.put("Xinjiang", new String[] {"Xinjiang", "Urumqi", "Tianshan"});
        KNOWN_REGIONS.put("Tibet", new String[] {"Tibet", "Lhasa", "Chengguan"});
        KNOWN_REGIONS.put("Hainan", new String[] {"Hainan", "Haikou", "Longhua"});
    }

    @Override
    public ParsedAddress parse(String text) {
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "address text is required");
        }
        String normalized = correct(text.trim()).replace(',', ' ');
        String[] region = detectRegion(normalized);
        String detail = normalized;
        for (String part : region) {
            detail = detail.replace(part, " ");
        }
        detail = detail.replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(detail)) {
            detail = normalized;
        }
        return new ParsedAddress(region[0], region[1], region[2], detail);
    }

    private static String correct(String text) {
        String corrected = text;
        for (var entry : CORRECTIONS.entrySet()) {
            corrected = corrected.replace(entry.getKey(), entry.getValue());
        }
        return corrected;
    }

    private static String[] detectRegion(String text) {
        for (var entry : KNOWN_REGIONS.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return new String[] {"Unknown", "Unknown", "Unknown"};
    }
}
