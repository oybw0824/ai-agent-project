package com.nbcb.agent.skill.dynamic;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 只暴露单次请求快照的 SkillRegistry。
 */
public class AgentScopedSkillRegistry implements SkillRegistry {

    private final AgentSkillSnapshot snapshot;
    private final SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate("""
            ## 可用技能
            {skills_list}

            {skills_load_instructions}""");

    public AgentScopedSkillRegistry(AgentSkillSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public Optional<SkillMetadata> get(String skillName) {
        return snapshot.get(skillName).map(this::toMetadata);
    }

    @Override
    public List<SkillMetadata> listAll() {
        return snapshot.skills().values().stream().map(this::toMetadata).toList();
    }

    @Override
    public boolean contains(String skillName) {
        return snapshot.skills().containsKey(skillName);
    }

    @Override
    public int size() {
        return snapshot.skills().size();
    }

    @Override
    public void reload() {
        // 请求快照不可变，不允许在执行过程中重载。
    }

    @Override
    public String readSkillContent(String skillName) throws IOException {
        VersionedSkill skill = snapshot.skills().get(skillName);
        if (skill == null) {
            throw new IOException("技能 [" + skillName + "] 不在当前请求快照中");
        }
        return skill.definition().content();
    }

    @Override
    public String getSkillLoadInstructions() {
        return "选择匹配的技能后，调用 read_skill 并传入 skill_name 获取完整执行指令。";
    }

    @Override
    public String getRegistryType() {
        return "database+nas";
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return systemPromptTemplate;
    }

    private SkillMetadata toMetadata(VersionedSkill skill) {
        SkillDefinition definition = skill.definition();
        return SkillMetadata.builder()
                .name(definition.name())
                .description(definition.description())
                .skillPath(definition.filePath())
                .source("nas:" + skill.version())
                .fullContent(definition.content())
                .build();
    }
}
