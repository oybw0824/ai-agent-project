package com.nbcb.agent.skill.dynamic;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicSkillsAgentHookTest {

    @Test
    void shouldOnlyInjectCurrentImmutableSnapshot() {
        AgentSkillLocalStore store = new AgentSkillLocalStore();
        AgentSkillSnapshot snapshot = new AgentSkillSnapshot("skill-agent", Map.of(
                "general-assistant", new VersionedSkill("1.0.0",
                        new SkillDefinition("general-assistant", "description",
                                "instructions", "SKILL.md"))));
        store.replaceSnapshot("skill-agent", snapshot);
        DynamicSkillsAgentHook hook = new DynamicSkillsAgentHook("skill-agent", store);

        Map<String, Object> injected = hook.beforeAgent(null, null).join();

        assertThat(injected.get(DynamicSkillsAgentHook.SNAPSHOT_STATE_KEY)).isSameAs(snapshot);
    }

    @Test
    void shouldFailWithServiceUnavailableWhenSnapshotIsNotReady() {
        DynamicSkillsAgentHook hook = new DynamicSkillsAgentHook(
                "skill-agent", new AgentSkillLocalStore());

        assertThatThrownBy(() -> hook.beforeAgent(null, null).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DynamicSkillException.class)
                .satisfies(ex -> assertThat(((DynamicSkillException) ex.getCause()).getErrorCode())
                        .isEqualTo(DynamicSkillErrorCode.SKILL_SNAPSHOT_NOT_READY));
    }
}
