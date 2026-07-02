@echo off
chcp 65001 >nul
echo Rewriting AgentStreamService.java...

(
echo package com.nbcb.agent.service;
echo.
echo import com.alibaba.cloud.ai.graph.NodeOutput;
echo import com.alibaba.cloud.ai.graph.OverAllState;
echo import com.alibaba.cloud.ai.graph.agent.ReactAgent;
echo import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
echo import com.nbcb.agent.domain.StreamEvent;
echo import com.nbcb.agent.domain.ToolCallRecord;
echo import com.nbcb.agent.skill.LoggingToolCallback;
echo import com.nbcb.agent.skill.NacosSkillRegistry;
echo import lombok.extern.slf4j.Slf4j;
echo import org.springframework.stereotype.Service;
echo import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
echo.
echo import java.io.IOException;
echo import java.util.LinkedHashMap;
echo import java.util.List;
echo import java.util.Map;
echo import java.util.concurrent.ExecutorService;
echo import java.util.concurrent.Executors;
echo import java.util.concurrent.atomic.AtomicReference;
) > agent-server\src\main\java\com\nbcb\agent\service\AgentStreamService.java

echo Done - header written
echo Now use IDE to edit the file manually
