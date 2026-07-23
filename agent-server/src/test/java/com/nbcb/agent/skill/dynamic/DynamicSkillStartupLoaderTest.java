package com.nbcb.agent.skill.dynamic;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicSkillStartupLoaderTest {

    @Test
    void shouldPreloadAllSkills() {
        DynamicSkillProperties properties = properties();
        DynamicSkillRuntimeService runtimeService = Mockito.mock(DynamicSkillRuntimeService.class);
        when(runtimeService.reloadAll("skill-agent"))
                .thenReturn(new AgentSkillSnapshot("skill-agent", Map.of()));

        new DynamicSkillStartupLoader(properties, runtimeService).run(null);

        verify(runtimeService).reloadAll("skill-agent");
    }

    @Test
    void shouldKeepApplicationRunningWhenPreloadFails() {
        DynamicSkillProperties properties = properties();
        DynamicSkillRuntimeService runtimeService = Mockito.mock(DynamicSkillRuntimeService.class);
        when(runtimeService.reloadAll("skill-agent"))
                .thenThrow(new DynamicSkillException(
                        DynamicSkillErrorCode.SKILL_FILE_UNAVAILABLE, "NAS 不可用"));

        assertThatCode(() -> new DynamicSkillStartupLoader(properties, runtimeService).run(null))
                .doesNotThrowAnyException();
    }

    private DynamicSkillProperties properties() {
        DynamicSkillProperties properties = new DynamicSkillProperties();
        properties.setAgentName("skill-agent");
        return properties;
    }
}
