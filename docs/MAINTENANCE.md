# OrangeChat 维护说明

## 项目来源与目标

本仓库来源于 `sue1231513/orangechat`。当前维护目标是以稳定性为优先，在可靠基线上持续维护 OrangeChat 的独有功能。

## 分支与合并策略

- 采用稳定 OrangeChat 基线加选择性 backport 的维护方式，不盲目追随或整仓合并最新 RikkaHub 上游。
- `master` 只接受经过测试和审查的变更。
- 功能开发、修复和维护工作均使用独立分支，并通过 Pull Request 合并。
- Debug CI 成功是合并前的最低门槛；未通过验证的变更不得合并到 `master`。

## 构建与安全

- Release 签名与 Debug 签名必须严格分离。公开的 Debug 签名材料不得用于 Release。
- 禁止向仓库提交 API Key、密码、Token、签名私钥或其他 secrets。
- 需要真实 Release 签名、外部账号或受保护凭据的流程必须由授权维护者单独配置和执行。
- 涉及高风险 Android 权限、自动工具调用或扩大外部系统访问范围的改动必须单独审查。

## 上游更新关注范围

选择性评估上游更新时，优先关注：

- Android 兼容性
- AI Provider/API 兼容性
- MCP 协议兼容性
- 安全漏洞
- 数据迁移与备份兼容性
