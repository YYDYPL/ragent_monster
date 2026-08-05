# API、配置与 Prompt

## 1. API 通用约定

默认基址：

```text
http://localhost:9090/api/ragent
```

除 `/auth/**` 和 `/error` 外，主服务接口都经过 Sa-Token 登录检查。Token 放在 `Authorization` 头。

普通接口返回：

```json
{
  "code": "0",
  "message": null,
  "data": {}
}
```

例外：

- `/rag/v3/chat` 返回 `text/event-stream`；
- 文档 `/file` 直接写二进制响应；
- 少数控制器方法声明 `void`，由 Spring 产生空 body；
- `/rag/eval` 只有 `app.eval.enabled=true` 时注册。

## 2. 80 个接口目录

以下目录按 20 个控制器、80 个映射注解整理。路径均不含 `/api/ragent` 前缀。

### 2.1 认证与用户（8）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/login` | 登录，唯一公开业务入口 |
| POST | `/auth/logout` | 退出 |
| GET | `/user/me` | 当前用户 |
| GET | `/users` | 用户分页，方法内检查 admin |
| POST | `/users` | 创建用户，方法内检查 admin |
| PUT | `/users/{id}` | 更新用户，方法内检查 admin |
| DELETE | `/users/{id}` | 删除用户，方法内检查 admin |
| PUT | `/user/password` | 当前用户修改密码 |

### 2.2 会话与聊天（9）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/rag/v3/chat` | SSE 问答 |
| POST | `/rag/v3/stop?taskId=` | 停止生成 |
| GET | `/conversations` | 当前用户会话 |
| PUT | `/conversations/{conversationId}` | 重命名 |
| DELETE | `/conversations/{conversationId}` | 删除 |
| GET | `/conversations/{conversationId}/messages` | 消息列表 |
| POST | `/conversations/messages/{messageId}/feedback` | 赞踩 |
| DELETE | `/conversations/messages/{messageId}/feedback` | 取消反馈 |
| POST | `/conversations/messages/{messageId}/recommended-questions` | 生成推荐追问 |

### 2.3 知识库（23）

| 资源 | 接口 |
| --- | --- |
| Knowledge Base（6） | `POST /knowledge-base`；`PUT/DELETE/GET /knowledge-base/{kb-id}`；`GET /knowledge-base`；`GET /knowledge-base/chunk-strategies` |
| Document（11） | `POST /knowledge-base/{kb-id}/docs/upload`；`POST /knowledge-base/docs/{doc-id}/chunk`；`DELETE /knowledge-base/docs/{doc-id}`；`GET/PUT /knowledge-base/docs/{docId}`；`GET /knowledge-base/{kb-id}/docs`；`GET /knowledge-base/docs/search`；`PATCH /knowledge-base/docs/{docId}/enable`；`GET /knowledge-base/docs/{docId}/chunk-logs`；`GET /knowledge-base/docs/{docId}/preview`；`GET /knowledge-base/docs/{docId}/file` |
| Chunk（6） | `GET/POST /knowledge-base/docs/{doc-id}/chunks`；`PUT/DELETE /knowledge-base/docs/{doc-id}/chunks/{chunk-id}`；`PATCH /knowledge-base/docs/{doc-id}/chunks/{chunk-id}/enable`；`PATCH /knowledge-base/docs/{doc-id}/chunks/batch-enable` |

路径变量命名同时使用 `doc-id` 和 `docId`，调用时 URL 无差异，但维护接口文档时要注意源码注解名称。

### 2.4 摄取流水线（10）

| 资源 | 接口 |
| --- | --- |
| Pipeline（5） | `POST /ingestion/pipelines`；`PUT/GET/DELETE /ingestion/pipelines/{id}`；`GET /ingestion/pipelines` |
| Task（5） | `POST /ingestion/tasks`；`POST /ingestion/tasks/upload`；`GET /ingestion/tasks/{id}`；`GET /ingestion/tasks/{id}/nodes`；`GET /ingestion/tasks` |

### 2.5 意图与术语（12）

| 资源 | 接口 |
| --- | --- |
| Intent Tree（7） | `GET /intent-tree/trees`；`POST /intent-tree`；`PUT/DELETE /intent-tree/{id}`；`POST /intent-tree/batch/enable`；`POST /intent-tree/batch/disable`；`POST /intent-tree/batch/delete` |
| Query Mapping（5） | `GET/POST /mappings`；`GET/PUT/DELETE /mappings/{id}` |

### 2.6 管理、Trace 与配置（12）

| 资源 | 接口 |
| --- | --- |
| Dashboard（3） | `GET /admin/dashboard/overview`；`GET /admin/dashboard/performance`；`GET /admin/dashboard/trends` |
| Knowledge Graph（2） | `GET /admin/kg/graph`；`GET /admin/kg/labels` |
| RAG Trace（3） | `GET /rag/traces/runs`；`GET /rag/traces/runs/{traceId}`；`GET /rag/traces/runs/{traceId}/nodes` |
| Business Log（2） | `GET /biz-change-logs`、`/biz-change-logs/{id}` |
| Settings（1） | `GET /rag/settings` |
| Eval（1） | `GET /rag/eval`，条件启用 |

### 2.7 示例问题（6）

| 方法 | 路径 |
| --- | --- |
| GET | `/rag/sample-questions` |
| GET | `/sample-questions` |
| GET | `/sample-questions/{id}` |
| POST | `/sample-questions` |
| PUT | `/sample-questions/{id}` |
| DELETE | `/sample-questions/{id}` |

## 3. 授权现状

服务端全局规则是“除登录外都需登录”，并不是“`/admin` 或管理页面对应接口都需 admin”。当前显式角色检查仅覆盖用户管理的四个接口。知识库、意图、Pipeline、Trace、系统设置、Dashboard、审计日志和示例问题管理均未发现同等后端角色校验。

因此不能根据前端路由推断服务端权限；详见[代码审查报告](12-code-review-report.md)。

## 4. 配置层次

主配置为 [application.yaml](../../bootstrap/src/main/resources/application.yaml)，MCP 服务配置为 [application.yml](../../mcp-server/src/main/resources/application.yml)。

| 前缀 | 内容 |
| --- | --- |
| `server` | 端口、context path |
| `spring.datasource` | PostgreSQL/Hikari |
| `spring.data.redis` | Redis |
| `rocketmq` | NameServer、Producer |
| `milvus` | Milvus URI |
| `rag.storage` | S3/OSS、bucket、公开 URL |
| `rag.vector` | `pg` 或 `milvus` |
| `rag.keyword` | `none` 或 `es` |
| `rag.graph` | LightRAG、检索/入库行为 |
| `rag.default` | collection、维度、SSE 超时 |
| `rag.query-rewrite/rerank` | RAG 阶段开关 |
| `rag.rate-limit/semaphore` | 聊天与上传并发 |
| `rag.memory` | 历史、摘要和标题 |
| `rag.knowledge.schedule` | 定时刷新 |
| `rag.mcp` | 远端 Server |
| `rag.search` | 通道、预算、RRF、权重 |
| `rag.trace` | Trace 开关与错误长度 |
| `rag.image-parse` | VLM 图片描述 |
| `ai` | Provider、模型、档位、熔断和流输出 |
| `mineru` | PDF 服务、轮询、OCR 与并发 |
| `sa-token` | Token 策略 |
| `app` | Demo 与 Eval |

## 5. 配置覆盖与秘密

Spring Boot 支持通过环境变量、系统属性和启动参数覆盖 YAML。仓库中的外部模型凭据使用环境变量表达式；本地数据库和 S3 兼容存储包含开发默认值。

生产环境应：

- 用 Secret Manager、容器 Secret 或受控环境变量覆盖；
- 不把实际凭据写回 YAML、日志、Trace 或管理接口；
- 为数据库、Redis、对象存储、RocketMQ 分配非默认用户和网络策略；
- 检查 `/rag/settings` 只返回掩码；
- 区分私有知识文件 URL 与可公开访问的资产 URL。

## 6. 关键配置不变量

| 配置 | 不变量 |
| --- | --- |
| `rag.search` | `recallBudget ≥ contextTopK`，候选池开启时也应 `candidateLimit ≥ contextTopK` |
| `rag.memory` | 摘要触发轮数必须大于近期保留轮数 |
| Chat Tier | 候选 ID 必须存在，超时为正，deep 候选支持 thinking |
| Embedding | 模型维度、向量表/collection 和知识库模型一致 |
| Graph | 后端、在线检索、写入同步三个开关分别判断 |
| Storage | bucket、endpoint、public URL 与 path-style 匹配实现 |
| Rate Limit | 许可租期覆盖业务，等待时间与前端体验一致 |

## 7. Prompt 资产

Prompt 位于 `bootstrap/src/main/resources/prompt`，由 [PromptTemplateLoader](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/core/prompt/PromptTemplateLoader.java) 加载和渲染。

| 文件 | 使用场景 |
| --- | --- |
| `answer-chat-system.st` | SYSTEM-only 普通聊天 |
| `answer-chat-kb.st` | 知识库回答 |
| `answer-chat-mcp.st` | MCP-only |
| `answer-chat-mcp-kb-mixed.st` | MCP + KB |
| `user-question-rewrite.st` | 查询改写与拆分 |
| `intent-classifier.st` | 意图分类 |
| `guidance-ambiguity-check.st` | 歧义判断 |
| `guidance-prompt.st` | 引导提示 |
| `mcp-parameter-extract.st` | MCP 参数提取系统约束 |
| `mcp-parameter-extract-user.st` | MCP 参数提取用户内容 |
| `context-format.st` | 摘要、证据、单/多问题等命名 section |
| `conversation-summary.st` | 会话摘要 |
| `conversation-title.st` | 会话标题 |
| `recommended-questions.st` | 推荐追问 |
| `pdf-format-guard.st` | PDF/MinerU 格式保护 |

`prompt/buckup` 下两份文件是历史备份，不在当前常量主路径中。

## 8. Prompt 的修改边界

修改 Prompt 是行为变更，不只是文案变更。至少检查：

- 输出 JSON 的任务是否仍能被严格解析；
- 模板 slot 名是否与 Java `Map` 一致；
- 单/多问题与 KB/MCP 混合 section 是否仍完整；
- 引用规则是否与前端 Source 展示一致；
- 是否引入 Prompt Injection 的越权指令；
- Token 长度是否挤压检索证据和历史；
- 改写、意图、摘要等快速档任务是否仍可在超时内完成。

当前没有 Prompt 快照或评测集自动回归，修改后需结合 `app.eval`、Trace 和固定问题集验证。

## 9. 默认服务与端口

| 服务 | 默认地址 |
| --- | --- |
| Frontend | `http://localhost:5173` |
| 主服务 | `http://localhost:9090/api/ragent` |
| MCP Server | `http://localhost:9099` |
| PostgreSQL | `127.0.0.1:5432` |
| Redis | `127.0.0.1:16379` |
| RocketMQ NameServer | `127.0.0.1:9876` |
| S3 兼容对象存储 | `http://localhost:9000` |
| Milvus（可选） | `http://localhost:19530` |
| Elasticsearch（可选） | `http://127.0.0.1:9200` |
| LightRAG（可选） | `http://127.0.0.1:9621` |

部署步骤与验证命令见[使用手册](../USER_GUIDE.md)。
