package com.nbcb.agent.skill.dynamic;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 当前服务实例按 Agent 隔离的不可变 Skill 快照存储。
 */
@Component
public class AgentSkillLocalStore {

    private final ConcurrentHashMap<String, AtomicReference<AgentSkillSnapshot>> snapshots =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> reloadLocks =
            new ConcurrentHashMap<>();

    public AgentSkillSnapshot getSnapshot(String agentName) {
        AtomicReference<AgentSkillSnapshot> reference = snapshots.get(agentName);
        return reference != null ? reference.get() : null;
    }

    public AgentSkillSnapshot requireReadySnapshot(String agentName) {
        AgentSkillSnapshot snapshot = getSnapshot(agentName);
        if (snapshot == null) {
            throw new DynamicSkillException(DynamicSkillErrorCode.SKILL_SNAPSHOT_NOT_READY,
                    "Skill 快照尚未加载，请先执行重新加载: " + agentName);
        }
        return snapshot;
    }

    public void replaceSnapshot(String agentName, AgentSkillSnapshot snapshot) {
        snapshots.computeIfAbsent(agentName, ignored -> new AtomicReference<>()).set(snapshot);
    }

    public <T> T withReloadLock(String agentName, Supplier<T> action) {
        ReentrantLock lock = reloadLocks.computeIfAbsent(agentName, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
