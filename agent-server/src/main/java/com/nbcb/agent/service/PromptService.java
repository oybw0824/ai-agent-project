package com.nbcb.agent.service;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosPromptListener;
import com.alibaba.nacos.api.ai.listener.NacosPromptEvent;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.nbcb.agent.skill.NacosSkillLoader;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★ 统一提示词服务 — Nacos 为主 + 本地文件同步 + 动态热更新
 * <p>
 * 优先级：Nacos 控制台 Prompt 管理 > 本地 {@code src/main/resources/prompt/*.md}
 * <p>
 * 核心能力：
 * <ul>
 *   <li>启动时扫描本地 prompt/*.md → 加载到 {@code localFallback} 缓存</li>
 *   <li>Nacos 可用时，通过 {@link AiService#subscribePrompt} 订阅变更 → 自动热更新</li>
 *   <li>★ 从 Nacos 拉取到提示词后，自动回写本地 .md 文件保持同步</li>
 *   <li>{@link #getSystemPrompt(String)} 统一入口，业务层无需感知来源</li>
 *   <li>Nacos 不可用/key 不存在时，自动降级使用本地文件</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class PromptService {

    /** 本地 prompt 文件扫描路径（类路径，启动时读取） */
    private static final String PROMPT_LOCATION = "classpath:prompt/*.md";

    /** ★ 本地 prompt 源码目录（Nacos 回写目标，确保写入源文件而非构建输出） */
    @Value("${prompt.local.dir:src/main/resources/prompt}")
    private String promptDir;

    /** ★ 额外从 Nacos 加载的 prompt key（本地不存在但 Nacos 有管理的） */
    @Value("${prompt.nacos.extra-keys:}")
    private String extraNacosKeys;

    /** Nacos 优先缓存（热更新目标） */
    private final ConcurrentHashMap<String, String> promptCache = new ConcurrentHashMap<>();

    /** 本地文件兜底缓存（启动时从类路径加载） */
    private final ConcurrentHashMap<String, String> localFallback = new ConcurrentHashMap<>();

    /** Nacos AI 服务客户端（复用 NacosSkillLoader 的连接） */
    private final AiService aiService;
    private final boolean nacosAvailable;

    public PromptService(NacosSkillLoader skillLoader) {
        this.aiService = skillLoader.getAiService();
        this.nacosAvailable = skillLoader.isNacosAvailable();
    }

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        // 1. 加载本地提示词文件作为兜底
        loadLocalPrompts();

        // 2. Nacos 可用时，从 Nacos 加载并订阅变更
        if (nacosAvailable) {
            loadFromNacos();
        } else {
            log.warn("Nacos 不可用，提示词仅使用本地文件兜底");
        }
    }

    // ==================== ★ 统一访问入口 ====================

    /**
     * 获取系统提示词
     * <p>
     * 查找顺序：Nacos 缓存 → 本地文件兜底
     *
     * @param promptKey 提示词 key（如 "agent-system"）
     * @return 提示词正文，保证非 null
     */
    public String getSystemPrompt(String promptKey) {
        // Nacos 优先
        String nacosPrompt = promptCache.get(promptKey);
        if (nacosPrompt != null && !nacosPrompt.isEmpty()) {
            return nacosPrompt;
        }

        // 降级到本地文件
        String localPrompt = localFallback.get(promptKey);
        if (localPrompt != null && !localPrompt.isEmpty()) {
            log.debug("提示词 [{}] 使用本地文件兜底", promptKey);
            return localPrompt;
        }

        // 最终兜底
        log.warn("提示词 [{}] 在 Nacos 和本地均未找到，返回空字符串", promptKey);
        return "";
    }

    /**
     * 获取指定版本/标签的提示词（直接从 Nacos 拉取，不使用缓存）
     *
     * @param promptKey 提示词 key
     * @param version   版本号（null 表示最新）
     * @param label     标签（null 表示默认）
     * @return 提示词正文
     */
    public String getSystemPrompt(String promptKey, String version, String label) {
        if (!nacosAvailable) {
            return getSystemPrompt(promptKey);
        }

        try {
            Prompt prompt;
            if (version != null && !version.isEmpty()) {
                prompt = aiService.getPromptByVersion(promptKey, version);
            } else if (label != null && !label.isEmpty()) {
                prompt = aiService.getPromptByLabel(promptKey, label);
            } else {
                prompt = aiService.getPrompt(promptKey);
            }

            if (prompt != null) {
                String content = prompt.getTemplate();
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }
        } catch (Exception e) {
            log.warn("从 Nacos 获取提示词 [{}] version={} label={} 失败: {}",
                    promptKey, version, label, e.getMessage());
        }
        return getSystemPrompt(promptKey);
    }

    // ==================== 缓存状态查询 ====================

    /** 获取所有已缓存的提示词 key（调试用） */
    public Map<String, String> getCachedPrompts() {
        Map<String, String> all = new ConcurrentHashMap<>(localFallback);
        all.putAll(promptCache); // Nacos 覆盖本地
        return all;
    }

    /** Nacos 是否可用 */
    public boolean isNacosAvailable() {
        return nacosAvailable;
    }

    // ==================== 本地文件加载 ====================

    /**
     * 扫描 {@code classpath:prompt/*.md}，加载到本地兜底缓存。
     * 文件名（不含 .md 后缀）作为 promptKey，同时保存 File 引用供回写。
     */
    private void loadLocalPrompts() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(PROMPT_LOCATION);

            if (resources.length == 0) {
                log.warn("未找到本地提示词文件（{}），将以空兜底运行", PROMPT_LOCATION);
                return;
            }

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) {
                    continue;
                }

                // 文件名去掉 .md 作为 promptKey
                String promptKey = filename.substring(0, filename.length() - 3);
                try {
                    String content = resource.getContentAsString(StandardCharsets.UTF_8).trim();
                    localFallback.put(promptKey, content);
                    log.info("★ 加载本地提示词: key={}, length={}", promptKey, content.length());
                } catch (IOException e) {
                    log.error("读取本地提示词文件失败: {}", filename, e);
                }
            }

            log.info("本地提示词加载完成：共 {} 个", localFallback.size());
        } catch (IOException e) {
            log.error("扫描本地提示词目录失败: {}", e.getMessage(), e);
        }
    }

    // ==================== Nacos 加载 + 订阅 ====================

    /**
     * 从 Nacos 加载已有 promptKey 的提示词，并订阅变更。
     * <p>
     * 加载范围：本地已有的 promptKey + 配置的额外 Nacos-only key。
     * <p>
     * ★ 热更新机制：Nacos 推送变更 → 回调中重新拉取最新内容 →
     * 更新缓存 + 回写本地 .md 文件，下次调用立即生效且本地文件保持同步。
     */
    private void loadFromNacos() {
        // 合并本地 key 和额外 Nacos key
        java.util.LinkedHashSet<String> allKeys = new java.util.LinkedHashSet<>(localFallback.keySet());
        if (extraNacosKeys != null && !extraNacosKeys.isBlank()) {
            for (String key : extraNacosKeys.split(",")) {
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) {
                    allKeys.add(trimmed);
                }
            }
        }

        for (String promptKey : allKeys) {
            try {
                // 订阅 Nacos Prompt 变更（推送式热更新）
                // subscribePrompt(key, version, label, listener)
                Prompt nacosPrompt = aiService.subscribePrompt(
                        promptKey,
                        null,  // version: null = 最新版本
                        null,  // label: null = 默认标签
                        new AbstractNacosPromptListener() {
                            @Override
                            public void onEvent(NacosPromptEvent event) {
                                String key = event.getPromptKey();
                                log.info("★ Nacos 推送 Prompt 变更: key={}", key);
                                // ★ 主动重新拉取最新内容 → 更新缓存 + 回写本地文件
                                try {
                                    Prompt latest = aiService.getPrompt(key);
                                    if (latest != null) {
                                        String template = latest.getTemplate();
                                        if (template != null && !template.isEmpty()) {
                                            promptCache.put(key, template);
                                            syncToLocalFile(key, template);
                                            log.info("★ 热更新 Prompt 成功: key={}, length={}",
                                                    key, template.length());
                                        } else {
                                            promptCache.remove(key);
                                            log.info("Prompt [{}] 内容为空，已清除缓存，将使用本地兜底", key);
                                        }
                                    } else {
                                        promptCache.remove(key);
                                        log.info("Prompt [{}] 已删除，已清除缓存", key);
                                    }
                                } catch (Exception e) {
                                    log.warn("热更新 Prompt [{}] 失败: {}，已清除缓存将使用本地兜底",
                                            key, e.getMessage());
                                    promptCache.remove(key);
                                }
                            }
                        });

                if (nacosPrompt != null) {
                    String content = nacosPrompt.getTemplate();
                    if (content != null && !content.isEmpty()) {
                        promptCache.put(promptKey, content);
                        // ★ Nacos 加载成功后，回写本地 .md 文件保持同步
                        syncToLocalFile(promptKey, content);
                        log.info("★ 从 Nacos 加载提示词并同步本地: key={}, length={}",
                                promptKey, content.length());
                    } else {
                        log.info("提示词 [{}] 在 Nacos 中内容为空，将使用本地文件兜底", promptKey);
                    }
                } else {
                    log.info("提示词 [{}] 在 Nacos 中不存在，将使用本地文件兜底", promptKey);
                }
            } catch (Exception e) {
                log.warn("从 Nacos 加载提示词 [{}] 失败: {}，将使用本地文件兜底",
                        promptKey, e.getMessage());
            }
        }

        log.info("Nacos 提示词加载完成：Nacos 缓存 {} 个，本地兜底 {} 个",
                promptCache.size(), localFallback.size());
    }

    // ==================== ★ 本地文件同步 ====================

    /**
     * 将 Nacos 拉取到的提示词回写到本地 .md 源文件，保持两端一致。
     * <p>
     * 使用 {@code prompt.local.dir} 配置的源码目录构建文件路径，
     * 确保写入 {@code src/main/resources/prompt/} 而非构建输出目录。
     * 目录不存在时自动创建。
     *
     * @param promptKey 提示词 key
     * @param content   从 Nacos 获取的最新内容
     */
    private void syncToLocalFile(String promptKey, String content) {
        try {
            File dir = new File(promptDir);
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                if (!created) {
                    log.warn("无法创建本地提示词目录: {}", dir.getAbsolutePath());
                    return;
                }
                log.info("★ 创建本地提示词目录: {}", dir.getAbsolutePath());
            }
            File file = new File(dir, promptKey + ".md");
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            // ★ 同步更新 localFallback，确保降级时读到最新内容
            localFallback.put(promptKey, content);
            log.info("★ 提示词 [{}] 已同步到本地源文件: {}", promptKey, file.getAbsolutePath());
        } catch (IOException e) {
            log.warn("回写本地提示词源文件失败: key={}, dir={}, error={}",
                    promptKey, promptDir, e.getMessage());
        }
    }
}
