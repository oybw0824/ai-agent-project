package com.nbcb.agent.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★ 统一提示词服务 — 从 classpath:prompt/*.md 加载
 * <p>
 * 启动时扫描 classpath 下的 prompt 目录，加载所有 .md 文件作为系统提示词。
 * 文件名（不含 .md 后缀）作为 promptKey。
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class PromptService {

    /** 本地 prompt 文件扫描路径 */
    private static final String PROMPT_LOCATION = "classpath:prompt/*.md";

    /** 提示词缓存 */
    private final ConcurrentHashMap<String, String> promptCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadLocalPrompts();
    }

    // ==================== 统一访问入口 ====================

    /**
     * 获取系统提示词
     *
     * @param promptKey 提示词 key（如 "agent-system"），对应 prompt/{key}.md 文件
     * @return 提示词正文，找不到时返回空字符串
     */
    public String getSystemPrompt(String promptKey) {
        String prompt = promptCache.get(promptKey);
        if (prompt != null && !prompt.isEmpty()) {
            return prompt;
        }
        log.warn("提示词 [{}] 未找到，返回空字符串", promptKey);
        return "";
    }

    /**
     * 获取指定版本/标签的提示词（兼容旧 API，实际忽略 version/label 参数）
     *
     * @param promptKey 提示词 key
     * @param version   已忽略（原 Nacos 参数，保留兼容）
     * @param label     已忽略（原 Nacos 参数，保留兼容）
     * @return 提示词正文
     */
    public String getSystemPrompt(String promptKey, String version, String label) {
        return getSystemPrompt(promptKey);
    }

    /** 获取所有已缓存的提示词（调试用） */
    public Map<String, String> getCachedPrompts() {
        return new ConcurrentHashMap<>(promptCache);
    }

    /** 提示词服务是否可用（始终为 true，本地文件总是可用） */
    public boolean isNacosAvailable() {
        return false;
    }

    // ==================== 本地文件加载 ====================

    private void loadLocalPrompts() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(PROMPT_LOCATION);

            if (resources.length == 0) {
                log.warn("未找到本地提示词文件（{}），将以空缓存运行", PROMPT_LOCATION);
                return;
            }

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) continue;

                String promptKey = filename.substring(0, filename.length() - 3);
                try {
                    String content = resource.getContentAsString(StandardCharsets.UTF_8).trim();
                    promptCache.put(promptKey, content);
                    log.info("★ 加载提示词: key={}, length={}", promptKey, content.length());
                } catch (IOException e) {
                    log.error("读取提示词文件失败: {}", filename, e);
                }
            }

            log.info("★ 提示词加载完成：共 {} 个", promptCache.size());
        } catch (IOException e) {
            log.error("扫描提示词目录失败: {}", e.getMessage(), e);
        }
    }
}
