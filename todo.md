# dcu-sglang-cc — sglang Anthropic 接口代理服务

## 项目目标

写一个代理服务，代理 **sglang 的 Anthropic 接口**（`/v1/messages`），在请求/响应经过时进行拦截，
修复 sglang 当前版本的一些兼容性问题，并做请求统计。

参考工程：`/mnt/d/IDEA_me/work/mygithub/lbrad`（同样是代理 OpenAI / Anthropic 的工程，
Spring Boot 3.2.4 / Java 21，分 `lbrad-common` 与 `lbrad-server` 两个模块）。

## 工程结构决策

- **单模块**：本工程功能比 lbrad 少，暂不拆 `common` / `server`，单 Maven 模块即可。
- **统计存储用 SQLite**：为便于做统计接口，把用于统计的轻量字段落一张 SQLite 表
  （便于部署，单文件数据库）；全量请求/响应体仍写 jsonl（见下）。

## 要代理的 sglang 后端

```yaml
inner-name: "chat_min"
base-url:   "http://sk-ai:19800"
```

- 接口：`POST {base-url}/v1/messages`（Anthropic 协议）
- 请求头：`x-api-key: sk-ai`、`anthropic-version: 2023-06-01`
- 已验证连通：非流式、流式均 200；两个待修复问题均在此后端复现。

---

## 需要修复的问题

### 修复 1：system role 不支持

**问题**：当前 sglang 版本不支持 `assistant` / `user` 之外的其它角色（如 `system`、`tool` 等）。
客户端（Claude Code 等）发来的 `messages` 里可能带有这些角色，sglang 会直接报错。

**修复方式**：拦截请求，把 `messages` 中所有非 `assistant` 的 role 统一改成 `user`。
（参考 lbrad 的 `AnthropicService.rebuildRequest`，`system2user` 开关控制：）

```java
if (!"assistant".equals(message.getRole())) {
    message.setRole("user");
}
```

**配置**：独立开关，默认关闭。
```yaml
fix:
  system-role: false   # 是否把非 assistant 的 role 改成 user
```

---

### 修复 2：客户端不能接收多个 tool_use

**问题**：sglang 的返回里，一条 assistant 消息的 `content` 数组中可能包含**多个** `tool_use`
块（模型一次并行调用多个工具）。但客户端（Claude Code）一次只能处理一个 `tool_use`，
收到多个会出错。

**示例返回**（需要拦截掉第 2 个及之后的 `tool_use`，只保留第一个）：
```json
{
  "id": "msg_3c35c53397f54d1a8d89e5dd5d8ec75a",
  "type": "message",
  "role": "assistant",
  "content": [
    { "type": "text", "text": "I'll first explore the environment..." },
    { "type": "tool_use", "id": "call_64be...", "name": "Bash", "input": { "command": "..." } },
    { "type": "tool_use", "id": "call_a750...", "name": "Bash", "input": { "command": "..." } }
  ],
  "model": "qwen38",
  "stop_reason": "tool_use",
  "usage": { "input_tokens": 50090, "output_tokens": 311 }
}
```

**修复方式**：拦截响应，`content` 中只保留**第一个** `tool_use`，丢弃第 2 个及之后的 `tool_use`
块（`text` 等其它块保留）。

**注意**：需要同时处理**流式**（SSE）和**非流式**两种返回。
- 非流式：直接改 `content` 数组。
- 流式：`tool_use` 通过 `content_block_start` / `content_block_delta` / `content_block_stop`
  事件流式下发，需要在转发给客户端时拦截掉第 2 个及之后的 `tool_use` 相关事件。

**配置**：独立开关，默认关闭。
```yaml
fix:
  single-tool-use: false   # 是否只保留第一个 tool_use，丢弃其余
```

---

## 统计项

统计基于每次响应返回的 `usage`（`input_tokens` / `output_tokens`）。

### 1. 可用用户配置（鉴权）

- 一个**可用用户配置文件**，配置每个用户的**用户名**和 **apiKey**。
- 客户端请求必须携带**正确的 apiKey** 才能访问服务（否则拒绝）。
- 通过 apiKey 反查用户名，用于后续明细记录与统计。

### 2. 请求明细记录（SQLite 统计表 + jsonl 全量日志）

每次请求记录一条明细，**分两处存**：

**(a) SQLite 统计表**（轻量字段，供统计接口查询/聚合，便于部署）：
- **请求 id**（logId）
- **用户 id**（由 apiKey 反查的用户名）
- **usage**：`input_tokens` / `output_tokens`
- **耗时**（cost，ms）
- **model**、**是否流式**、**时间戳**、**stop_reason** 等

**(b) jsonl 全量日志**（请求体 + 响应体等大字段，用于排查/训练，对齐 lbrad `TrainingDataService`）：
- 生产-消费模式：业务线程入队，后台单线程异步批量写 jsonl（消除锁竞争）。
- 文件滚动：超过大小阈值（如 20MB）或定时，切换新文件。
- 滚动时把旧文件 **gzip 压缩**（`.jsonl` → `.jsonl.gz`），压缩成功后删原文件。

> 分工：SQLite 存「能聚合的统计字段」，jsonl 存「完整请求/响应体」。统计页面读 SQLite。

### 3. 统计页面

基于 **SQLite 统计表**，提供统计接口 + 页面（参考 lbrad 用 Vue + D3 的静态页方式），至少包含：
- **用户 token 使用情况**：按用户聚合 input/output token、请求数。
- **服务处理请求的速度**：QPS / 单位时间请求数、耗时分布。
- **当前服务状态**：在线、当前并发、近期请求趋势等。

> 具体页面字段/图表待与用户进一步确认。

---

## 待办清单

- [ ] 搭建工程骨架（pom、Spring Boot 启动类、配置类、application.yml）
- [ ] 可用用户配置 + apiKey 鉴权（正确 apiKey 才放行）
- [ ] 实现 Anthropic `/v1/messages` 代理（流式 + 非流式）
- [ ] 修复 1：system role → user（独立开关）
- [ ] 修复 2：多 tool_use 只保留第一个（独立开关，流式 + 非流式）
- [ ] 请求明细记录：SQLite 统计表（统计字段）+ jsonl 全量日志（请求/响应体，定期 gzip 压缩）
- [ ] 统计接口 + 统计页面：用户 token 使用 / 请求速度 / 服务状态（数据源 SQLite）
