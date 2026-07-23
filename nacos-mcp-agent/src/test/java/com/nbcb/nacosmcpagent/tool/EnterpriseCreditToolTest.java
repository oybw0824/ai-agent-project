package com.nbcb.nacosmcpagent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnterpriseCreditToolTest {

    private final EnterpriseCreditTool creditTool =
            new EnterpriseCreditTool("nacos-mcp-agent");

    @Test
    void shouldReturnKnownEnterpriseCreditSummary() {
        EnterpriseCreditTool.EnterpriseCreditResult result =
                creditTool.queryEnterpriseCredit(
                        EnterpriseCreditTool.SAMPLE_CODE);

        assertThat(result.found()).isTrue();
        assertThat(result.provider()).isEqualTo("nacos-mcp-agent");
        assertThat(result.enterpriseName()).isEqualTo("北京示例科技有限公司");
        assertThat(result.creditRating()).isEqualTo("AA");
        assertThat(result.creditScore()).isEqualTo(86);
        assertThat(result.riskLevel()).isEqualTo("LOW");
        assertThat(result.reportDate()).hasToString("2026-07-15");
    }

    @Test
    void shouldReturnNotFoundForUnknownCode() {
        EnterpriseCreditTool.EnterpriseCreditResult result =
                creditTool.queryEnterpriseCredit("91110000UNKNOWN01");

        assertThat(result.found()).isFalse();
        assertThat(result.enterpriseName()).isNull();
        assertThat(result.conclusion()).contains("未查询到");
    }

    @Test
    void shouldRejectBlankCode() {
        assertThatThrownBy(() -> creditTool.queryEnterpriseCredit(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }
}
