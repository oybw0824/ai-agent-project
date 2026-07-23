package com.nbcb.agent.skill.dynamic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicSkillRuntimeServiceTest {

    @Test
    void shouldForceReadSameVersionOnEveryFullReload() {
        Fixture fixture = new Fixture(binding("general-assistant", "v1", true));

        fixture.service.reloadAll("skill-agent");
        fixture.service.reloadAll("skill-agent");

        assertThat(fixture.loader.loadCount()).isEqualTo(2);
        assertThat(fixture.service.currentSnapshot("skill-agent")
                .get("general-assistant").orElseThrow().version()).isEqualTo("v1");
    }

    @Test
    void shouldKeepStartedRequestSnapshotAfterExplicitReload() throws Exception {
        Fixture fixture = new Fixture(binding("general-assistant", "v1", true));
        AgentSkillSnapshot oldRequest = fixture.service.reloadAll("skill-agent");

        fixture.repository.bindings.set(List.of(binding("general-assistant", "v2", true)));
        AgentSkillSnapshot newRequest = fixture.service.reloadAll("skill-agent");

        assertThat(new AgentScopedSkillRegistry(oldRequest)
                .readSkillContent("general-assistant")).contains("v1");
        assertThat(new AgentScopedSkillRegistry(newRequest)
                .readSkillContent("general-assistant")).contains("v2");
    }

    @Test
    void shouldPreserveOldSnapshotWhenFullReloadFails() {
        Fixture fixture = new Fixture(binding("general-assistant", "v1", true));
        fixture.service.reloadAll("skill-agent");
        fixture.repository.bindings.set(List.of(binding("general-assistant", "v2", true)));
        fixture.loader.failedVersion.set("v2");

        assertThatThrownBy(() -> fixture.service.reloadAll("skill-agent"))
                .isInstanceOf(DynamicSkillException.class);

        assertThat(fixture.service.currentSnapshot("skill-agent")
                .get("general-assistant").orElseThrow().version()).isEqualTo("v1");
    }

    @Test
    void shouldAtomicallyReplaceAllSkillsAndRemoveDisabledBinding() {
        Fixture fixture = new Fixture(
                binding("skill-a", "v1", true),
                binding("skill-b", "v1", true));
        fixture.service.reloadAll("skill-agent");

        fixture.repository.bindings.set(List.of(
                binding("skill-a", "v2", true),
                binding("skill-b", "v1", false)));
        AgentSkillSnapshot replacement = fixture.service.reloadAll("skill-agent");

        assertThat(replacement.skills()).containsOnlyKeys("skill-a");
        assertThat(replacement.get("skill-a").orElseThrow().version()).isEqualTo("v2");
    }

    @Test
    void shouldReloadSingleSkillWithoutChangingOthers() {
        Fixture fixture = new Fixture(
                binding("skill-a", "v1", true),
                binding("skill-b", "v1", true));
        fixture.service.reloadAll("skill-agent");
        fixture.repository.bindings.set(List.of(
                binding("skill-a", "v2", true),
                binding("skill-b", "v1", true)));

        fixture.service.reload("skill-agent", "skill-a");
        AgentSkillSnapshot snapshot = fixture.service.currentSnapshot("skill-agent");

        assertThat(snapshot.get("skill-a").orElseThrow().version()).isEqualTo("v2");
        assertThat(snapshot.get("skill-b").orElseThrow().version()).isEqualTo("v1");
    }

    @Test
    void shouldNotExposePartialSnapshotDuringFullReload() throws Exception {
        Fixture fixture = new Fixture(
                binding("skill-a", "v1", true),
                binding("skill-b", "v1", true));
        fixture.service.reloadAll("skill-agent");
        fixture.repository.bindings.set(List.of(
                binding("skill-a", "v2", true),
                binding("skill-b", "v2", true)));
        fixture.loader.blockVersion.set("v2");

        CompletableFuture<AgentSkillSnapshot> reload = CompletableFuture.supplyAsync(
                () -> fixture.service.reloadAll("skill-agent"));
        assertThat(fixture.loader.loadStarted.await(2, TimeUnit.SECONDS)).isTrue();

        AgentSkillSnapshot duringReload = fixture.service.currentSnapshot("skill-agent");
        assertThat(duringReload.get("skill-a").orElseThrow().version()).isEqualTo("v1");
        assertThat(duringReload.get("skill-b").orElseThrow().version()).isEqualTo("v1");

        fixture.loader.continueLoad.countDown();
        AgentSkillSnapshot replacement = reload.get(2, TimeUnit.SECONDS);
        assertThat(replacement.get("skill-a").orElseThrow().version()).isEqualTo("v2");
        assertThat(replacement.get("skill-b").orElseThrow().version()).isEqualTo("v2");
    }

    @Test
    void shouldRejectUnknownAgentMissingBindingAndUnreadySnapshot() {
        Fixture fixture = new Fixture(binding("general-assistant", "v1", true));

        assertThatThrownBy(() -> fixture.service.reloadAll("other-agent"))
                .isInstanceOfSatisfying(DynamicSkillException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(DynamicSkillErrorCode.AGENT_NOT_FOUND));
        assertThatThrownBy(() -> fixture.service.currentSnapshot("skill-agent"))
                .isInstanceOfSatisfying(DynamicSkillException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(DynamicSkillErrorCode.SKILL_SNAPSHOT_NOT_READY));
        assertThatThrownBy(() -> fixture.service.reload("skill-agent", "missing"))
                .isInstanceOfSatisfying(DynamicSkillException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(DynamicSkillErrorCode.AGENT_SKILL_NOT_BOUND));
    }

    private static AgentSkillBinding binding(String skillName, String version, boolean enabled) {
        return new AgentSkillBinding("skill-agent", skillName, version,
                skillName + "/" + version + "/SKILL.md", enabled);
    }

    private static final class Fixture {
        private final FakeRepository repository;
        private final FakeLoader loader = new FakeLoader();
        private final AgentSkillLocalStore store = new AgentSkillLocalStore();
        private final DynamicSkillRuntimeService service;

        private Fixture(AgentSkillBinding... bindings) {
            DynamicSkillProperties properties = new DynamicSkillProperties();
            properties.setAgentName("skill-agent");
            repository = new FakeRepository(List.of(bindings));
            service = new DynamicSkillRuntimeService(properties, repository, loader, store);
        }
    }

    private static final class FakeRepository implements AgentSkillBindingRepository {
        private final AtomicReference<List<AgentSkillBinding>> bindings;

        private FakeRepository(List<AgentSkillBinding> bindings) {
            this.bindings = new AtomicReference<>(bindings);
        }

        @Override
        public List<AgentSkillBinding> findEnabledByAgentName(String agentName) {
            return bindings.get().stream().filter(AgentSkillBinding::enabled).toList();
        }

        @Override
        public Optional<AgentSkillBinding> find(String agentName, String skillName) {
            return bindings.get().stream()
                    .filter(binding -> binding.skillName().equals(skillName))
                    .findFirst();
        }
    }

    private static final class FakeLoader implements SkillFileLoader {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> failedVersion = new AtomicReference<>();
        private final AtomicReference<String> blockVersion = new AtomicReference<>();
        private final CountDownLatch loadStarted = new CountDownLatch(1);
        private final CountDownLatch continueLoad = new CountDownLatch(1);

        @Override
        public SkillDefinition load(String skillName, String version, String filePath) {
            calls.incrementAndGet();
            if (version.equals(failedVersion.get())) {
                throw new DynamicSkillException(DynamicSkillErrorCode.SKILL_FILE_UNAVAILABLE,
                        "模拟 NAS 加载失败");
            }
            if (version.equals(blockVersion.get())) {
                loadStarted.countDown();
                try {
                    if (!continueLoad.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待并发测试继续加载超时");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("并发测试加载被中断", ex);
                }
            }
            return new SkillDefinition(skillName, "description-" + version,
                    "instructions-" + version, filePath);
        }

        int loadCount() {
            return calls.get();
        }
    }
}
