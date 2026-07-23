package com.nbcb.nacosmcpagent.agent;

import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 解析节点提示词。数据库可保存正文，也可保存 classpath/file 资源地址。
 */
public class PromptTemplateLoader {

    private final ResourceLoader resourceLoader;
    private final Environment environment;

    public PromptTemplateLoader(
            ResourceLoader resourceLoader,
            Environment environment) {
        this.resourceLoader = resourceLoader;
        this.environment = environment;
    }

    public String loadRequired(String configuredValue, String fieldName) {
        String value = load(configuredValue);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(fieldName + " 不能为空");
        }
        return value;
    }

    public String load(String configuredValue) {
        if (!StringUtils.hasText(configuredValue)) {
            return "";
        }
        String value = environment.resolvePlaceholders(
                configuredValue.trim());
        if (!value.startsWith("classpath:")
                && !value.startsWith("file:")) {
            return value;
        }
        Resource resource = resourceLoader.getResource(value);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词资源不存在: " + value);
        }
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new IllegalStateException("读取提示词资源失败: " + value, ex);
        }
    }
}
