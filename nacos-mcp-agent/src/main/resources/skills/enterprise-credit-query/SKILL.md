---
name: enterprise-credit-query
description: Enterprise credit query for corporate customers by unified social credit code.
---

# 企业征信查询

## 触发场景

当用户需要查询企业客户征信、信用评级、风险等级、逾期金额、负债余额或征信报告摘要时，使用本技能。

## 必要信息

调用工具前必须获得企业统一社会信用代码。缺少代码时先询问用户补充，不要猜测。

## 执行流程

1. 调用动态注入的 `queryEnterpriseCredit` 工具。
2. `found=false` 时明确说明未查询到数据，不编造评级、评分或风险结论。
3. `found=true` 时汇总企业名称、评级、评分、风险等级、逾期金额、负债余额、报告日期、`provider` 和结论。
4. 最终回答中的统一社会信用代码只展示前 4 位和后 4 位，中间使用 `****`。

## 输出要求

- 明确说明结果是验证工程内置的模拟数据，不是真实征信报告。
- 必须展示 `provider` 和报告日期。
- 不推断工具没有返回的信息。
