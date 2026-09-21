# dcu-sglang-cc

把 **sglang** 的 `/v1/messages` 适配成 **Claude Code** 能直接用的 Anthropic 接口，
并在中间加一层用户鉴权、并发门、统计与用户管理。

一句话：让跑在国产 GPU 上的开源模型，能当 Claude Code 的后端用。

## 背景

- **硬件**：海光 K100_AI
- **后端**：sglang 跑 Qwen3.8 模型，对外暴露 Anthropic 风格的 `/v1/messages`
- **问题**：sglang 的 `/v1/messages` 与真实 Anthropic 协议存在若干出入
  （角色约束、流式 delta 混型等），Claude Code 直接连会报错、流被判定损坏
- **本工具**：一个中间代理。把请求转发给 sglang，在中间把协议"洗"成
  Claude Code 认得的形状，同时提供鉴权、限流、统计、用户管理这些基础能力

## 功能

- **协议适配**：修 sglang 与 Anthropic 的兼容性问题（见下"协议修复"）
- **用户鉴权**：`x-api-key` / `Authorization: Bearer`，内存缓存 + SQLite 持久化
- **并发门**：两级公平信号量（并发许可 + 排队许可），排队满或超时返回 503
- **用户级并发**：每个用户最多同时跑 `max_concurrency` 个请求（默认 2，管理页可配），
  超限立即返回 429（不排队）
- **统计**：可聚合指标（token、耗时、成功率、改动行数）写 SQLite，
  完整请求/响应体写滚动 jsonl，未知字段路径单独观测
- **用户管理**：管理页增删改用户，登录后维护
- **前端**：Vue 3 + D3 单页应用（无构建步骤），服务统计 / 用户统计 / 用户管理 / 使用说明

### 协议修复（`dcu.fix.*`，各自独立开关）

- **`system-role`**：sglang 只认 `assistant` / `user` 两种 role，
  把 `system` / `tool` 等角色归并成 `user`
- **`mismatched-delta`**：sglang 输出 ≥2 个 tool call 时，会把两个 tool JSON 之间
  的分隔文本以 `text_delta` 塞进第一个 `tool_use` 块的 index，导致 Claude Code 报
  `Content block is not a text block`。`StreamFix` 丢弃与所在块类型不匹配的 delta

## sglang 适配器补丁（`sglang-patch/`）

sglang **v0.5.12** 的 Anthropic 适配器（`srt/entrypoints/anthropic/`）有两个缺陷，
**v0.5.14 才修复**：

1. **缓存命中不报**：`--enable-cache-report` 开启后，OpenAI 端点会返回
   `prompt_tokens_details.cached_tokens`，但 Anthropic 端点从不把它转成
   `cache_read_input_tokens`，统计页看不到任何命中
2. **思考内容不输出**：`--reasoning-parser qwen3` 解析出的 `reasoning_content`
   被适配器丢弃，Anthropic 端点拿不到模型的思考内容

`sglang-patch/` 里是参考 v0.5.14 实现、对 v0.5.12 两个文件做的最小化补丁：

| 文件 | 覆盖到容器内 | 改动 |
|---|---|---|
| `serving.py` | `.../sglang/srt/entrypoints/anthropic/serving.py` | 新增 `_anthropic_usage()`：`input_tokens` 改为"未命中部分"（prompt − cached），命中部分单独放 `cache_read_input_tokens`（非流式 + 流式 `message_delta` 都带）；非流式响应加 `thinking` 块；流式加 `thinking_delta` 事件（含正确的 block start/stop 序列） |
| `protocol.py` | `.../sglang/srt/entrypoints/anthropic/protocol.py` | `AnthropicDelta` 增加 `thinking_delta` 类型与 `thinking` 字段 |

> 语义变化（与 v0.5.14 一致）：`input_tokens` 是**未命中部分**，
> 总输入 = `input_tokens + cache_read_input_tokens`。
> 本代理的缓存命中率统计按此口径计算。

**部署方式**：sglang 容器每次启动都删除重建，`docker cp` 进去的文件会丢，
所以补丁文件放在宿主机 `/mydata/sglang-patch/`，启动脚本
（`/mydata/start/sglang_qwen38_chat_min.sh`）挂载该目录并在容器启动时
`cp` 覆盖到 sglang 安装目录，每次重启自动重新打补丁。

升级 sglang 到 ≥0.5.14 后本补丁不再需要（官方已含这两处修复），
届时删掉启动脚本里的挂载与 `cp` 两行即可。

## 架构

### 请求主链路

```
AnthropicController (/v1/messages)
  → 鉴权（x-api-key 或 Bearer，UserRegistry 内存缓存）
  → UserConcurrencyGate.acquire(user, limit)（用户级：超限 429，不排队）
  → ConcurrencyGate.acquire()（全局两级信号量：并发 + 排队，满/超时 503）
  → DcuService.handle
      → RequestFix.fixSystemRole（sglang 只认 assistant/user）
      → SglangClient.send / sendStream（okhttp，model 改写为 innerModel）
      → 流式：StreamFix 过滤 → StreamResponseCollector 收集 → 原样转发
      → 非流式：直接透传
  → recordStat（SQLite 统计 + jsonl 全量日志）
```

### 统计 / 观测管线（三条异步写入，同一模式）

三者都是 **BlockingQueue + 虚拟线程消费**（生产-消费，热路径零阻塞）：

| 组件 | 写什么 | 文件 |
|---|---|---|
| `StatsStore` | 可聚合统计字段 | `data/stats.db`（SQLite，WAL） |
| `JsonlLogService` | 完整 request + response 体 | `data/request_*.jsonl`（20MB 滚动 + gzip） |
| `UnmodeledFieldObserver` | 客户端发了但代理未建模的字段路径 | `data/unmodeled-fields.jsonl` |

`logId`（UUID）在 Controller 生成，贯穿三条管线 + 日志，是关联回查的键。

### 其它设计要点

- **SQLite schema 迁移**：手写版 Flyway（`SchemaMigrator`），DDL 收在
  `db/migration/V<版本>__<描述>.sql`，版本号 = 文件名前缀，存 `schema_version` 表。
  已发布的迁移文件不可再改，只能追加。
- **未知字段保真**：请求 POJO 用 `@JsonAnySetter`/`@JsonAnyGetter` 收进 `extras`，
  客户端带新参数不会 500 也不会丢字段。
- **model 改写**：`SglangClient` 把 `request.model` 改为 `innerModel`；
  统计侧用请求快照，保证记录的是客户端真正请求的 model 名。
- **上游错误透传**：`UpstreamException` 携带原始 status + body，
  保留 429/5xx 可重试语义，不吞成 500。
- **客户端断开**：流式回调里轮询 `writer.checkError()`，检测到后 `call.cancel()`
  中断上游读取，抛 `ClientGoneException` 区分于上游错误。

## 快速开始

```bash
# 构建 + 测试
mvn test

# 本地起服务（context path /dcu-sglang-cc，统计写在 ./data）
PORT=18888 mvn spring-boot:run
```

- Java 21，Spring Boot 3.2.4，Maven 构建
- 起服务后访问 `http://localhost:<port>/dcu-sglang-cc/` 打开统计页

## 配置

所有配置在 `application.yml` 的 `dcu.*` 前缀下，由 `DcuConfiguration` 绑定：

| 配置 | 说明 |
|---|---|
| `dcu.backend` | sglang 后端地址、对内模型名（`inner-name`）、api-key |
| `dcu.fix.*` | 修复开关（`system-role`、`mismatched-delta`），各自独立 |
| `dcu.stats.*` | jsonl 目录、文件大小阈值、SQLite 路径 |
| `dcu.users` | 首次启动 seed 到 user 表（之后在管理页维护） |
| `dcu.admin` | 管理员账号（登录 `/admin` 维护用户） |
| 超时 / 并发 / 排队 | `connect-timeout`、`read-timeout`、`max-concurrency`、`queue-size`、`queue-wait-timeout` |

> 注意：`application.yml` 里的 `dcu.backend.api-key`、`dcu.admin`、`dcu.users`
> 是真实凭据，**不要提交到公开仓库**。

## 作为 Claude Code 后端使用

1. 安装 Node.js 18+ 与 Claude Code：`npm install -g @anthropic-ai/claude-code`
2. 编辑 `~/.claude/settings.json`（Windows 为 `用户目录/.claude/settings.json`）：

```json
{
  "env": {
    "ANTHROPIC_AUTH_TOKEN": "<your_api_key>",
    "ANTHROPIC_BASE_URL": "http://<host>:<port>/dcu-sglang-cc",
    "API_TIMEOUT_MS": "3600000",
    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
    "CLAUDE_CODE_AUTO_COMPACT_WINDOW": "131072"
  },
  "tui": "fullscreen"
}
```

3. 在项目目录执行 `claude` 启动会话。`<your_api_key>` 需联系管理员申请。

> 完整步骤、常用命令、已知问题见服务自带的 `guide.html`（统计页顶部导航"使用说明"）。

## 统计与管理

- **服务统计**（公开）：总请求数、成功/失败、输入/输出 token、改动行数、平均耗时、
  请求趋势、按模型分布
- **用户统计 / 明细下钻**（需登录）：按用户聚合、单条记录回查
- **用户管理**（需登录）：增删改用户与 api-key
- 管理接口（`/admin/users/**`、`/stats/records/**`、`/stats/users/**`）由
  `AdminAuthInterceptor` 拦截，要求 HttpSession 登录态

"改动行数"指标：从响应的 tool_use 块算 AI 改了多少行文件——
**Write 取 `input.content` 行数、Edit 取 `input.new_string` 行数**，
一次响应多个块累加，Bash/Read 等不计（口径见 `LinesChangedCalculator`）。

## 套娃（dogfooding）

本项目本身就是**拿自己当 Claude Code 后端**开发出来的：
启动本服务 → 把 Claude Code 的 `ANTHROPIC_BASE_URL` 指向它 →
用这个 Claude Code（背后是 sglang 上的 Qwen3.8）继续改本项目的代码。
也就是说，帮你写这个代理的 AI，正是通过这个代理、由国产 GPU 上的开源模型提供的。

## 技术栈

Java 21 · Spring Boot 3.2.4 · Maven · Lombok · OkHttp · SQLite JDBC ·
Vue 3 + D3（无构建步骤）· JUnit 5
