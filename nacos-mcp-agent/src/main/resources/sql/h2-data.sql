MERGE INTO AI_MODEL KEY (PK_ID) VALUES (
    '00000000000000000000000000000001',
    'deepseek-main', 'PUBLISHED', 'deepseek',
    'deepseek-chat',
    'https://api.deepseek.com',
    'env:DEEPSEEK_API_KEY', '0',
    'system', '2026-07-20 00:00:00',
    '0000000000', 'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_AGENT KEY (PK_ID) VALUES (
    '00000000000000000000000000000002',
    'credit-agent', '征信与天气助手', 'PUBLISHED', '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_SKILL KEY (PK_ID) VALUES (
    '00000000000000000000000000000003',
    'skill-credit', 'enterprise-credit-query', 'PUBLISHED', '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_SKILL KEY (PK_ID) VALUES (
    '00000000000000000000000000000004',
    'skill-weather', 'local-weather-query', 'PUBLISHED', '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_AGENT_NODE KEY (PK_ID) VALUES (
    '00000000000000000000000000000005',
    'credit-agent-node', 'credit-agent',
    'classpath:prompt/system-prompt.md', '', 'deepseek-main',
    0.20, 2048, '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_AGENT_NODE_SKILL_BINDING KEY (PK_ID) VALUES (
    '00000000000000000000000000000006',
    'credit-agent-node', 'skill-credit', '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_AGENT_NODE_SKILL_BINDING KEY (PK_ID) VALUES (
    '00000000000000000000000000000007',
    'credit-agent-node', 'skill-weather', '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_TOOL KEY (PK_ID) VALUES (
    '00000000000000000000000000000008',
    'tool-credit', 'nacos-mcp-agent', 'queryEnterpriseCredit',
    '企业征信查询', '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_TOOL KEY (PK_ID) VALUES (
    '00000000000000000000000000000009',
    'tool-weather', 'nacos-mcp-agent', 'getWeatherByCity',
    '城市天气查询', '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_TOOL_BINDING KEY (PK_ID) VALUES (
    '00000000000000000000000000000010',
    'skill-credit', 'SKILL', 'tool-credit', '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_TOOL_BINDING KEY (PK_ID) VALUES (
    '00000000000000000000000000000011',
    'skill-weather', 'SKILL', 'tool-weather', '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);

MERGE INTO AI_TOOL_BINDING KEY (PK_ID) VALUES (
    '00000000000000000000000000000012',
    'credit-agent-node', 'AGENT_NODE', 'tool-weather', '0',
    'system', '2026-07-20 00:00:00', '0000000000',
    'system', '2026-07-20 00:00:00', '0000000000'
);
