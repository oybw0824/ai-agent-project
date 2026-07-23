package com.nbcb.nacosmcpagent.agent;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 当前 Agent 根据数据库绑定创建出的 SkillRegistry 适配器。
 */
class DatabaseBoundSkillRegistry implements SkillRegistry {

    private final Map<String, SkillMetadata> skills;
    private final String classpathRoot;
    private final SystemPromptTemplate systemPromptTemplate;

    DatabaseBoundSkillRegistry(
            Map<String, SkillMetadata> skills,
            String classpathRoot,
            SystemPromptTemplate systemPromptTemplate) {
        this.skills = Map.copyOf(skills);
        this.classpathRoot = classpathRoot;
        this.systemPromptTemplate = systemPromptTemplate;
    }

    @Override
    public Optional<SkillMetadata> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    @Override
    public List<SkillMetadata> listAll() {
        return List.copyOf(skills.values());
    }

    @Override
    public boolean contains(String name) {
        return skills.containsKey(name);
    }

    @Override
    public int size() {
        return skills.size();
    }

    @Override
    public void reload() {
        // Agent 启动时按数据库绑定加载；配置变更后通过重启应用生效。
    }

    @Override
    public String readSkillContent(String name) {
        SkillMetadata metadata = skills.get(name);
        if (metadata == null) {
            throw new IllegalStateException("Skill not found: " + name);
        }
        return metadata.getFullContent();
    }

    @Override
    public String getSkillLoadInstructions() {
        return "**Skill Location:**\n"
                + "- **Classpath Skills**: `classpath:"
                + classpathRoot + "/{skillCode}/SKILL.md`\n\n"
                + "**Skill Path Format:**\n"
                + "Use the exact skill id shown above when calling "
                + "`read_skill` to read the SKILL.md content.\n";
    }

    @Override
    public String getRegistryType() {
        return "DatabaseBoundClasspathSkill";
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return systemPromptTemplate;
    }
}
