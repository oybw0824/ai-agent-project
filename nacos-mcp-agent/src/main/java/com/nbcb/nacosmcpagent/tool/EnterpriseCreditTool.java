package com.nbcb.nacosmcpagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 企业征信模拟工具。本地调用，同时通过本工程 MCP Server 注册到 Nacos。
 */
@Slf4j
@Service
@LocalMcpTool
@ConditionalOnProperty(
        prefix = "mcp.tools.credit",
        name = "enabled",
        havingValue = "true")
public class EnterpriseCreditTool {

    public static final String SAMPLE_CODE = "91110000MA01NB001X";

    private static final Map<String, CreditData> CREDIT_DATA = Map.of(
            SAMPLE_CODE,
            new CreditData(
                    "北京示例科技有限公司",
                    "AA",
                    86,
                    "LOW",
                    new BigDecimal("0.00"),
                    new BigDecimal("1280000.00"),
                    LocalDate.of(2026, 7, 15),
                    "模拟征信结果显示该企业履约记录稳定，当前未发现逾期记录。")
    );

    private final String provider;

    public EnterpriseCreditTool(
            @Value("${spring.ai.mcp.server.name:nacos-mcp-agent}")
            String provider) {
        this.provider = provider;
    }

    @Tool(description = "查询企业客户模拟征信信息，返回企业名称、评级、评分、风险、余额、报告日期、结论和 provider")
    public EnterpriseCreditResult queryEnterpriseCredit(
            @ToolParam(description = "统一社会信用代码，例如 91110000MA01NB001X")
            String unifiedSocialCreditCode) {
        if (unifiedSocialCreditCode == null
                || unifiedSocialCreditCode.isBlank()) {
            throw new IllegalArgumentException("统一社会信用代码不能为空");
        }
        String normalizedCode = unifiedSocialCreditCode
                .trim().toUpperCase();
        CreditData data = CREDIT_DATA.get(normalizedCode);
        if (data == null) {
            log.info("企业征信模拟工具未查询到数据：provider={}, code={}",
                    provider, maskCode(normalizedCode));
            return EnterpriseCreditResult.notFound(
                    provider, normalizedCode);
        }
        log.info("企业征信模拟工具调用完成：provider={}, code={}",
                provider, maskCode(normalizedCode));
        return EnterpriseCreditResult.found(
                provider, normalizedCode, data);
    }

    private static String maskCode(String code) {
        if (code.length() <= 8) {
            return "****";
        }
        return code.substring(0, 4) + "****"
                + code.substring(code.length() - 4);
    }

    private record CreditData(
            String enterpriseName,
            String creditRating,
            int creditScore,
            String riskLevel,
            BigDecimal overdueAmount,
            BigDecimal liabilityBalance,
            LocalDate reportDate,
            String conclusion) {
    }

    /**
     * 企业征信工具结构化返回结果。
     */
    public record EnterpriseCreditResult(
            boolean found,
            String provider,
            String unifiedSocialCreditCode,
            String enterpriseName,
            String creditRating,
            Integer creditScore,
            String riskLevel,
            BigDecimal overdueAmount,
            BigDecimal liabilityBalance,
            LocalDate reportDate,
            String conclusion) {

        private static EnterpriseCreditResult found(
                String provider,
                String code,
                CreditData data) {
            return new EnterpriseCreditResult(
                    true,
                    provider,
                    code,
                    data.enterpriseName(),
                    data.creditRating(),
                    data.creditScore(),
                    data.riskLevel(),
                    data.overdueAmount(),
                    data.liabilityBalance(),
                    data.reportDate(),
                    data.conclusion());
        }

        private static EnterpriseCreditResult notFound(
                String provider,
                String code) {
            return new EnterpriseCreditResult(
                    false,
                    provider,
                    code,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "未查询到该统一社会信用代码对应的模拟征信数据。");
        }
    }
}
