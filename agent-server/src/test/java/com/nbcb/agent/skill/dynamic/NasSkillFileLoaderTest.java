package com.nbcb.agent.skill.dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NasSkillFileLoaderTest {

    Path root;

    @BeforeEach
    void createWorkspaceTempDirectory() throws Exception {
        root = Path.of("target", "dynamic-skill-tests", UUID.randomUUID().toString())
                .toAbsolutePath();
        Files.createDirectories(root);
    }

    @Test
    void shouldLoadValidVersionedSkill() throws Exception {
        Path file = writeSkill(root, "general-assistant", "1.1.0", "执行版本一规则");
        NasSkillFileLoader loader = loader(root);

        SkillDefinition definition = loader.load(
                "general-assistant", "1.1.0", root.relativize(file).toString());

        assertThat(definition.name()).isEqualTo("general-assistant");
        assertThat(definition.description()).isEqualTo("通用助手");
        assertThat(definition.content()).contains("执行版本一规则");
        assertThat(definition.content()).doesNotContain("---");
    }

    @Test
    void shouldRejectVersionMismatch() throws Exception {
        Path file = writeSkill(root, "general-assistant", "1.0.0", "旧版本");

        assertThatThrownBy(() -> loader(root).load(
                "general-assistant", "2.0.0", file.toString()))
                .isInstanceOfSatisfying(DynamicSkillException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(DynamicSkillErrorCode.SKILL_PARSE_FAILED));
    }

    @Test
    void shouldRejectPathOutsideConfiguredRoot() throws Exception {
        Path outsideRoot = Path.of("target", "outside-skill-tests", UUID.randomUUID().toString())
                .toAbsolutePath();
        Files.createDirectories(outsideRoot);
        Path outside = writeSkill(outsideRoot, "general-assistant", "1.1.0", "越界内容");

        assertThatThrownBy(() -> loader(root).load(
                "general-assistant", "1.1.0", outside.toString()))
                .isInstanceOfSatisfying(DynamicSkillException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(DynamicSkillErrorCode.SKILL_FILE_UNAVAILABLE));
    }

    static Path writeSkill(Path root, String name, String version, String body) throws Exception {
        Path directory = root.resolve(name).resolve(version);
        Files.createDirectories(directory);
        Path file = directory.resolve("SKILL.md");
        Files.writeString(file, """
                ---
                name: %s
                description: 通用助手
                version: %s
                ---

                # 执行指令

                %s
                """.formatted(name, version, body));
        return file;
    }

    private NasSkillFileLoader loader(Path root) {
        DynamicSkillProperties properties = new DynamicSkillProperties();
        properties.setNasRoot(root.toString());
        return new NasSkillFileLoader(properties);
    }
}
