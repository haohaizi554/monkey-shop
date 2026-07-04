package com.example.monkey.logistics.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class RuleBasedAddressParserTest {

    private final RuleBasedAddressParser parser = new RuleBasedAddressParser();

    @Test
    void parsesAndCorrectsKnownEnglishAddress() {
        var parsed = parser.parse("Zhejiang Hangzou Xihu Wenyi Road 100");

        assertThat(parsed.province()).isEqualTo("Zhejiang");
        assertThat(parsed.city()).isEqualTo("Hangzhou");
        assertThat(parsed.district()).isEqualTo("Xihu");
        assertThat(parsed.detail()).contains("Wenyi Road 100");
    }

    @Test
    void parsesKnownChineseAddressAndFailsClosedOnBlankInput() {
        var parsed = parser.parse("浙江 杭洲 西湖区 文一路100号");

        assertThat(parsed.province()).isEqualTo("浙江省");
        assertThat(parsed.city()).isEqualTo("杭州市");
        assertThat(parsed.district()).isEqualTo("西湖区");
        assertThatThrownBy(() -> parser.parse(" "))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
