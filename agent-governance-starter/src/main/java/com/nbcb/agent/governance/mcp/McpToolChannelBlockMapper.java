package com.nbcb.agent.governance.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * MCP 工具渠道禁用配置 Mapper。
 */
@Mapper
public interface McpToolChannelBlockMapper
        extends BaseMapper<McpToolChannelBlockEntity> {

    default List<McpToolChannelBlockEntity> findCandidates(String toolName,
                                                           String channelCode) {
        return selectList(new LambdaQueryWrapper<McpToolChannelBlockEntity>()
                .eq(McpToolChannelBlockEntity::getToolName, toolName)
                .eq(McpToolChannelBlockEntity::getChannelCode, channelCode)
                .eq(McpToolChannelBlockEntity::getIsDelete, "0"));
    }
}
