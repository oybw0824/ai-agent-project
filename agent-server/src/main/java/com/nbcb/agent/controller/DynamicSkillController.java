package com.nbcb.agent.controller;

import com.nbcb.agent.skill.dynamic.AgentSkillSnapshot;
import com.nbcb.agent.skill.dynamic.DynamicSkillRuntimeService;
import com.nbcb.agent.skill.dynamic.VersionedSkill;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指定 Agent、指定 Skill 的本实例重载接口。
 */
@RestController
@RequestMapping("/api/agents")
public class DynamicSkillController {

    private final DynamicSkillRuntimeService runtimeService;

    public DynamicSkillController(DynamicSkillRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /**
     * 强制重新加载当前 Agent 的全部启用 Skill。
     */
    @PostMapping("/{agentName}/skills/reload")
    public Map<String, Object> reloadAll(@PathVariable String agentName) {
        AgentSkillSnapshot snapshot = runtimeService.reloadAll(agentName);
        List<Map<String, String>> skills = snapshot.skills().entrySet().stream()
                .map(entry -> Map.of(
                        "skillName", entry.getKey(),
                        "version", entry.getValue().version()))
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentName", agentName);
        response.put("status", "RELOADED");
        response.put("skillCount", skills.size());
        response.put("skills", skills);
        return response;
    }

    @PostMapping("/{agentName}/skills/{skillName}/reload")
    public Map<String, Object> reload(@PathVariable String agentName,
                                      @PathVariable String skillName) {
        VersionedSkill loaded = runtimeService.reload(agentName, skillName);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentName", agentName);
        response.put("skillName", skillName);
        response.put("version", loaded.version());
        response.put("status", "RELOADED");
        return response;
    }
}
