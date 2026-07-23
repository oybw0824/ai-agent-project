# 动态 Skill 部署说明

运行时只从数据库绑定和 NAS 版本目录加载 Skill，不再扫描 classpath。

## 首次迁移

1. 将原有 `general-assistant.md` 的内容保存到 NAS 新版本目录：

   ```text
   <SKILL_NAS_ROOT>/general-assistant/1.1.0/SKILL.md
   ```

2. 确认文件可读，并确保 Front Matter 中的 `name` 为 `general-assistant`、`version` 为 `1.1.0`。
3. 在应用使用的数据源中写入绑定：

   ```sql
   INSERT INTO agent_skill_binding
       (agent_name, skill_name, skill_version, skill_file_path, enabled)
   VALUES
       ('skill-agent', 'general-assistant', '1.1.0',
        'general-assistant/1.1.0/SKILL.md', TRUE);
   ```

   `skill_file_path` 可以是相对于 `SKILL_NAS_ROOT` 的路径，也可以是根目录内的绝对路径。

使用 Docker Compose 时，通过 `SKILL_NAS_HOST_PATH` 指定宿主机已经准备好的 Skill 根目录；该目录会只读挂载到容器内的 `/nas/skills`。

## 发布新版本

1. 将完整文件写入新的、不可覆盖的版本目录。
2. 校验文件能够读取和解析。
3. 最后更新 `skill_version` 与 `skill_file_path`。
4. 通过 Skill 管理页面或以下接口显式刷新当前实例：

   ```http
   POST /api/agents/skill-agent/skills/reload

   # 或仅重新加载单个 Skill
   POST /api/agents/skill-agent/skills/general-assistant/reload
   ```

`DynamicSkillsAgentHook` 只注入当前内存快照，不会在对话请求中查询数据库或读取 NAS。
当前版本按单个 `agent-server` 实例部署；如果未来扩展为多实例，需要逐实例调用 Reload
或增加跨实例广播机制。

禁止先更新数据库再写文件，也禁止覆盖已发布的版本目录。
