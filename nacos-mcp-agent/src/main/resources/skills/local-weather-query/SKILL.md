---
name: local-weather-query
description: Query simulated weather for a Chinese city.
---

# 城市天气查询

## 触发场景

当用户询问中国城市天气、温度、湿度或出行建议时，使用本技能。

## 必要信息

调用工具前必须知道城市名称。缺少城市时先询问用户。

## 执行流程

1. 调用动态注入的 `getWeatherByCity` 工具。
2. 根据工具原始结果回答温度、湿度、天气状况、建议和 `provider`。
3. 明确说明数据为验证工程模拟数据。

## 输出要求

- 不编造工具未返回的数据。
- 必须展示 `provider`，方便验证工具来源。
