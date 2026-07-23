package com.nbcb.agent.skill.dynamic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 NAS 读取并严格校验 SKILL.md。
 */
@Slf4j
@Component
public class NasSkillFileLoader implements SkillFileLoader {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\R(.*?)\\R---\\s*\\R?(.*)$", Pattern.DOTALL);

    private final DynamicSkillProperties properties;

    public NasSkillFileLoader(DynamicSkillProperties properties) {
        this.properties = properties;
    }

    @Override
    public SkillDefinition load(String skillName, String version, String filePath) {
        Path skillFile = resolveSecurePath(filePath);
        String raw;
        try {
            if (!Files.isRegularFile(skillFile) || !Files.isReadable(skillFile)) {
                throw unavailable("Skill 文件不存在或不可读: " + skillName, null);
            }
            raw = Files.readString(skillFile, StandardCharsets.UTF_8);
        } catch (DynamicSkillException ex) {
            throw ex;
        } catch (IOException ex) {
            throw unavailable("读取 Skill 文件失败: " + skillName, ex);
        }

        Matcher matcher = FRONTMATTER.matcher(raw);
        if (!matcher.matches()) {
            throw parseFailed("Skill 缺少合法的 YAML Front Matter: " + skillName, null);
        }

        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object parsed = yaml.load(matcher.group(1));
            if (!(parsed instanceof Map<?, ?> frontmatter)) {
                throw parseFailed("Skill Front Matter 不是对象: " + skillName, null);
            }

            String declaredName = required(frontmatter, "name", skillName);
            String description = required(frontmatter, "description", skillName);
            if (!skillName.equals(declaredName)) {
                throw parseFailed("Skill 名称与数据库绑定不一致: " + skillName, null);
            }

            Object declaredVersion = frontmatter.get("version");
            if (declaredVersion != null && !version.equals(declaredVersion.toString().trim())) {
                throw parseFailed("Skill 版本与数据库绑定不一致: " + skillName, null);
            }

            String content = matcher.group(2).trim();
            if (content.isBlank()) {
                throw parseFailed("Skill 正文不能为空: " + skillName, null);
            }
            log.info("Skill 文件已加载: skill={}, version={}", skillName, version);
            return new SkillDefinition(skillName, description, content, skillFile.toString());
        } catch (DynamicSkillException ex) {
            throw ex;
        } catch (Exception ex) {
            throw parseFailed("Skill YAML 解析失败: " + skillName, ex);
        }
    }

    private Path resolveSecurePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw unavailable("数据库中的 Skill 文件路径为空", null);
        }
        try {
            Path root = Path.of(properties.getNasRoot()).toAbsolutePath().normalize().toRealPath();
            Path configured = Path.of(filePath);
            Path candidate = configured.isAbsolute() ? configured : root.resolve(configured);
            Path realFile = candidate.toAbsolutePath().normalize().toRealPath();
            if (!realFile.startsWith(root)) {
                throw unavailable("Skill 文件路径超出 NAS 根目录", null);
            }
            return realFile;
        } catch (DynamicSkillException ex) {
            throw ex;
        } catch (IOException | InvalidPathException ex) {
            throw unavailable("Skill 文件路径不可用", ex);
        }
    }

    private String required(Map<?, ?> values, String key, String skillName) {
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) {
            throw parseFailed("Skill 缺少必填字段 " + key + ": " + skillName, null);
        }
        return value.toString().trim();
    }

    private DynamicSkillException unavailable(String message, Throwable cause) {
        return new DynamicSkillException(DynamicSkillErrorCode.SKILL_FILE_UNAVAILABLE, message, cause);
    }

    private DynamicSkillException parseFailed(String message, Throwable cause) {
        return new DynamicSkillException(DynamicSkillErrorCode.SKILL_PARSE_FAILED, message, cause);
    }
}
