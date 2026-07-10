-- ============================================================
-- H2 Mock 数据 — Agent 治理配置测试用例
-- ============================================================

-- ★ ROUTE 路由配置：将 HTTP 路径映射到 Agent
INSERT INTO agent_governance_config (config_type, path_pattern, agent_name, is_stream, description)
VALUES ('ROUTE', '/api/v1/chat', 'skill-agent', 0, '同步对话路由');

INSERT INTO agent_governance_config (config_type, path_pattern, agent_name, is_stream, description)
VALUES ('ROUTE', '/api/v1/chat/stream', 'skill-agent', 1, '流式对话路由');

INSERT INTO agent_governance_config (config_type, path_pattern, agent_name, is_stream, description)
VALUES ('ROUTE', '/api/v1/skills', 'skill-agent', 0, '技能查询路由');

-- ★ AGENT 全局开关：skill-agent 默认启用
INSERT INTO agent_governance_config (config_type, agent_name, status, default_status, description)
VALUES ('AGENT', 'skill-agent', 'ENABLED', 'ENABLED', 'skill-agent 全量可用');

-- ★ USER 用户白名单：test-user-001 被显式放行
INSERT INTO agent_governance_config (config_type, agent_name, user_id, status, description)
VALUES ('USER', 'skill-agent', 'test-user-001', 'ENABLED', '测试用户白名单');

-- ★ USER 用户黑名单：test-user-disabled 被显式禁用
INSERT INTO agent_governance_config (config_type, agent_name, user_id, status, description)
VALUES ('USER', 'skill-agent', 'test-user-disabled', 'DISABLED', '测试用户黑名单');

-- ★ USER 时效性白名单：已过期用户
INSERT INTO agent_governance_config (config_type, agent_name, user_id, status, effective_from, effective_to, description)
VALUES ('USER', 'skill-agent', 'expired-user', 'ENABLED',
        '2026-01-01 00:00:00', '2026-06-01 00:00:00',
        '已过期的白名单用户');

-- ★ ORG 机构白名单：org-001 被显式放行
INSERT INTO agent_governance_config (config_type, agent_name, org_id, status, description)
VALUES ('ORG', 'skill-agent', 'org-001', 'ENABLED', '机构白名单');

-- ★ ORG 机构黑名单：org-disabled 被显式禁用
INSERT INTO agent_governance_config (config_type, agent_name, org_id, status, description)
VALUES ('ORG', 'skill-agent', 'org-disabled', 'DISABLED', '机构黑名单');
