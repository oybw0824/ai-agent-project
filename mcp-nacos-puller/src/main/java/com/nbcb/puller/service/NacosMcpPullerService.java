package com.nbcb.puller.service;

import com.nbcb.puller.domain.InstanceInfo;
import com.nbcb.puller.domain.McpPullResponse;
import com.nbcb.puller.domain.McpServiceSummary;
import com.nbcb.puller.domain.NacosServiceInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class NacosMcpPullerService {

    private static final String DEFAULT_NACOS_ADDR = "127.0.0.1:8848";
    private static final String DEFAULT_NAMESPACE = "public";
    private static final String DEFAULT_GROUP = "mcp-server";
    private static final String DEFAULT_CONSOLE_PORT = "8080";

    private final WebClient webClient;

    @Value("${NACOS_ADDR:" + DEFAULT_NACOS_ADDR + "}")
    private String nacosAddr;

    @Value("${NACOS_NAMESPACE:" + DEFAULT_NAMESPACE + "}")
    private String namespace;

    @Value("${NACOS_USERNAME:nacos}")
    private String username;

    @Value("${NACOS_PASSWORD:nacos}")
    private String password;

    @Value("${mcp.puller.group:" + DEFAULT_GROUP + "}")
    private String mcpGroup;

    @Value("${mcp.puller.console-port:" + DEFAULT_CONSOLE_PORT + "}")
    private String consolePort;

    @Value("${mcp.puller.login-enabled:true}")
    private boolean loginEnabled;

    private volatile String accessToken;

    public NacosMcpPullerService() {
        this.webClient = WebClient.builder().build();
    }

    public McpPullResponse pullAllMcpServices() {
        long start = System.currentTimeMillis();
        log.info("开始从 Nacos 拉取所有 MCP 服务... server={}, namespace={}, group={}",
                nacosAddr, namespace, mcpGroup);

        try {
            if (loginEnabled) {
                login();
            }

            List<McpServiceSummary> summaries = pullMcpServices();
            if (summaries.isEmpty()) {
                long elapsed = System.currentTimeMillis() - start;
                return McpPullResponse.builder()
                        .success(true)
                        .message("未发现 MCP 服务（group=" + mcpGroup + "），耗时 " + elapsed + "ms")
                        .totalServiceCount(0)
                        .totalInstanceCount(0)
                        .services(Collections.emptyList())
                        .build();
            }

            int totalInstances = summaries.stream()
                    .mapToInt(McpServiceSummary::getInstanceCount)
                    .sum();

            long elapsed = System.currentTimeMillis() - start;
            log.info("MCP 服务拉取完成：{} 个服务，{} 个实例，耗时 {}ms",
                    summaries.size(), totalInstances, elapsed);

            return McpPullResponse.builder()
                    .success(true)
                    .message("拉取成功，耗时 " + elapsed + "ms")
                    .totalServiceCount(summaries.size())
                    .totalInstanceCount(totalInstances)
                    .services(summaries)
                    .build();

        } catch (Exception e) {
            log.error("从 Nacos 拉取 MCP 服务失败", e);
            return McpPullResponse.builder()
                    .success(false)
                    .message("拉取失败: " + e.getMessage())
                    .totalServiceCount(0)
                    .totalInstanceCount(0)
                    .services(Collections.emptyList())
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private void login() {
        try {
            String loginUrl = buildBaseUrl() + "/nacos/v1/auth/login";
            String body = "username=" + username + "&password=" + password;

            Map<String, Object> response = webClient.post()
                    .uri(loginUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("accessToken")) {
                accessToken = (String) response.get("accessToken");
                log.info("Nacos 登录成功");
            } else {
                log.warn("Nacos 登录响应中未找到 accessToken，认证可能未开启");
            }
        } catch (Exception e) {
            log.warn("Nacos 登录失败（认证可能未开启）: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<McpServiceSummary> pullMcpServices() {
        String consoleBaseUrl = buildConsoleBaseUrl();

        String mcpListUrl = UriComponentsBuilder.fromHttpUrl(consoleBaseUrl + "/v3/console/ai/mcp/list")
                .queryParam("namespaceId", namespace)
                .queryParam("search", "blur")
                .queryParam("pageNo", 1)
                .queryParam("pageSize", 1000)
                .queryParam("username", username)
                .queryParam("accessToken", accessToken != null ? accessToken : "")
                .build()
                .toUriString();

        log.debug("请求 Nacos MCP 列表: {}", mcpListUrl);

        Map<String, Object> response = webClient.get()
                .uri(mcpListUrl)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        List<McpServiceSummary> mcpSummaries = new ArrayList<>();

        if (response != null && response.containsKey("data")) {
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            List<Map<String, Object>> pageItems = (List<Map<String, Object>>) data.get("pageItems");

            if (pageItems != null && !pageItems.isEmpty()) {
                log.info("从 MCP 列表 API 发现 {} 个服务", pageItems.size());

                for (Map<String, Object> item : pageItems) {
                    String serviceName = getStr(item, "name");
                    String version = getStr(item, "version");
                    String protocol = getStr(item, "protocol");
                    String status = getStr(item, "status");
                    List<String> capabilities = (List<String>) item.get("capabilities");
                    Boolean enabled = (Boolean) item.get("enabled");

                    if (serviceName != null) {
                        McpServiceSummary summary = McpServiceSummary.builder()
                                .serviceName(serviceName)
                                .groupName(mcpGroup)
                                .version(version != null ? version : "unknown")
                                .protocol(protocol != null ? protocol : "STREAMABLE")
                                .mcpEndpoint(null)
                                .instanceCount(0)
                                .healthyInstanceCount(0)
                                .build();
                        mcpSummaries.add(summary);
                    }
                }
            }
        }

        enrichWithServiceInfo(mcpSummaries, consoleBaseUrl);

        return mcpSummaries;
    }

    @SuppressWarnings("unchecked")
    private void enrichWithServiceInfo(List<McpServiceSummary> summaries, String consoleBaseUrl) {
        for (McpServiceSummary summary : summaries) {
            try {
                String serviceListUrl = UriComponentsBuilder
                        .fromHttpUrl(consoleBaseUrl + "/v3/console/ns/service/list")
                        .queryParam("pageNo", 1)
                        .queryParam("pageSize", 1000)
                        .queryParam("namespaceId", namespace)
                        .queryParam("accessToken", accessToken != null ? accessToken : "")
                        .build()
                        .toUriString();

                Map<String, Object> response = webClient.get()
                        .uri(serviceListUrl)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                if (response != null && response.containsKey("data")) {
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    List<Map<String, Object>> pageItems = (List<Map<String, Object>>) data.get("pageItems");

                    if (pageItems != null) {
                        for (Map<String, Object> item : pageItems) {
                            String name = getStr(item, "name");
                            if (name != null && name.equals(summary.getServiceName())) {
                                summary.setInstanceCount(getInt(item, "ipCount"));
                                summary.setHealthyInstanceCount(getInt(item, "healthyInstanceCount"));
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("获取服务 {} 的实例信息失败: {}", summary.getServiceName(), e.getMessage());
            }
        }
    }

    private String buildBaseUrl() {
        String addr = nacosAddr;
        if (!addr.startsWith("http://") && !addr.startsWith("https://")) {
            addr = "http://" + addr;
        }
        return addr;
    }

    private String buildConsoleBaseUrl() {
        String addr = nacosAddr;
        if (!addr.startsWith("http://") && !addr.startsWith("https://")) {
            addr = "http://" + addr;
        }
        String host = addr.replace("http://", "").replace("https://", "");
        int colonIndex = host.lastIndexOf(":");
        if (colonIndex > 0) {
            host = host.substring(0, colonIndex);
        }
        return "http://" + host + ":" + consolePort;
    }

    private static String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }
}