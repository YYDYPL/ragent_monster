# 测试与可观测性

## 1. 测试现状

当前仓库有 26 个 Java 测试类：

| 模块 | 生产 Java | 测试 Java | 特点 |
| --- | ---: | ---: | --- |
| `bootstrap` | 458 | 24 | 覆盖部分核心算法、配置、服务和外部集成 |
| `framework` | 39 | 0 | 无模块级自动测试 |
| `infra-ai` | 50 | 0 | 生产代码无本模块测试；部分路由测试放在 bootstrap |
| `mcp-server` | 6 | 2 | You.com 单元与环境条件 Live Test |

前端 `package.json` 没有 test 脚本，仓库中没有 Vitest/Jest/Playwright/Cypress 测试。

## 2. 后端测试地图

### 2.1 算法与纯逻辑

| 测试 | 关注点 |
| --- | --- |
| `ChunkPackerTest` | 结构化 Block 的装箱、边界和顺序 |
| `DeduplicationPostProcessorTest` | Chunk ID/内容哈希去重 |
| `DefaultContextFormatterTest` | KB/MCP 上下文格式 |
| `SearchChannelPropertiesTest` | 检索预算和配置解析 |
| `ChatTierConfigValidatorTest` | Chat 档位配置 |
| `ModelSelectorTest` | 模型候选、档位和 preferred model |

### 2.2 服务与持久化

| 测试 | 关注点 |
| --- | --- |
| `ConversationMessageServiceTests` | 会话消息查询与转换 |
| `JdbcConversationMemorySummaryServiceTest` | 摘要触发、合并和锁行为 |
| `IngestionPipelineServiceImplTest` | 流水线服务校验与转换 |
| `IngestionPipelineNodeMapperTest` | Pipeline 节点数据库映射 |
| `ScheduleRefreshProcessorTest` | 远端刷新状态与分支 |

### 2.3 RAG 行为

| 测试 | 关注点 |
| --- | --- |
| `QueryRewriteTests` | 单问题改写 |
| `MultiQuestionRewriteServiceTests` | 多问句拆分和兜底 |
| `IntentTreeServiceTests` | 意图树管理 |
| `SimpleIntentClassifierTests` | 简单意图分类 |
| `VectorTreeIntentClassifierTests` | 向量意图实验 |
| `WebSearchChannelTest` | You.com 响应、异常和配置 |

### 2.4 解析、向量与外部集成

| 测试 | 关注点 |
| --- | --- |
| `MinerUPdfUploadFlowTest` | MinerU PDF 远程流程 |
| `InvoiceIndexDocumentTests` | 发票文档索引场景 |
| `SiliconFlowEmbeddingServiceTests` | 真实/配置化 Embedding |
| `MilvusCollectionTests` | Milvus collection |
| `WebSearchChannelLiveTest` | 真实 You.com，需 `YDC_API_KEY` |
| `YouComSearchMcpExecutorTest` | MCP 搜索执行器的本地测试 |
| `YouComSearchMcpExecutorLiveTest` | 真实 You.com，需 `YDC_API_KEY` |
| `RagentCoreApplicationTests` | Spring 上下文 |

两个 `*LiveTest` 使用 `@EnabledIfEnvironmentVariable`，没有 `YDC_API_KEY` 时跳过。其他带外部依赖色彩的测试不一定全部有同样的显式隔离，执行前应查看其 mock、profile 和环境要求。

## 3. 安全执行构建

根 POM 把 `spotless:apply` 绑定到 `compile`。为了避免验证过程改写业务源码，学习和 CI 基线检查应显式禁用 apply：

```powershell
.\mvnw.cmd '-Dspotless.apply.skip=true' '-DskipTests' package
.\mvnw.cmd '-Dspotless.apply.skip=true' test
```

PowerShell 调用 `.cmd` 时应给 `-D...` 参数加引号，避免带点号的参数被错误拆分。当前基线的默认测试命令还受 Surefire `@{argLine}` 未初始化问题影响，详见[审查报告 CR-12](12-code-review-report.md#cr-12默认-maven-测试无法启动插件版本未固定)。`-DforkCount=0` 可用于确认测试逻辑，但它会改变测试进程模型，只是诊断手段。

若只验证确定性的单元测试，可用 Surefire 的 `-Dtest=...` 指定测试类。真实外部测试应在单独 profile/作业中提供凭据和服务。

前端：

```powershell
Set-Location frontend
npm run lint
npm run build
```

构建会生成忽略目录中的产物；执行后仍应检查 `git status`，确认没有源文件被格式化或代码生成覆盖。

### 3.1 本次基线验证结果

验证环境为 Windows、JDK 21，源码目标版本为 Java 17：

| 检查 | 结果 |
| --- | --- |
| 后端 `package` | 禁用 Spotless 写入后，父工程及 `framework`、`infra-ai`、`bootstrap`、`mcp-server` 全部成功 |
| 默认目标单测 | Fork JVM 因字面量 `@{argLine}` 未初始化而退出，0 项测试执行 |
| 同 JVM 目标单测 | 6 个类共 25 项：24 通过、1 失败；失败是 CRLF 工作树下的换行断言，见 CR-13 |
| 前端生产构建 | 成功，转换 4879 个模块 |
| 前端 Lint | 配置加载失败，尚未进入源码检查，见 CR-14 |

Vite 同时警告两个产物超过 500 kB：主入口约 3.45 MB（gzip 约 1.06 MB），Spreadsheet Preview 约 1.69 MB（gzip 约 507 kB）。这不影响构建成功，但二次开发应通过路由懒加载、重依赖隔离和 Bundle 分析设置性能预算。

本次没有为 MinerU、Milvus、SiliconFlow、You.com 等真实外部测试注入服务或凭据，因此没有把它们的未执行状态计为代码失败。

## 4. 当前测试缺口

按风险优先级，明显缺口包括：

1. 密码、角色和管理接口授权；
2. `taskId` 取消所有权；
3. SSE 全事件序列、断线重试、取消与重复终态；
4. `FairDistributedRateLimiter` 的多实例竞争与许可泄漏；
5. `StreamTaskManager` 跨实例取消；
6. `RoutingLLMService` 首包成功、超时、无内容和多候选切换；
7. Chat/Embedding/Rerank Client 的协议契约；
8. 知识库关系库与向量/关键词/图谱的失败补偿；
9. 任意 URL 抓取的安全边界；
10. 前端 `chatStore` 状态机和路由守卫。

`framework` 与 `infra-ai` 无本模块测试，使基础设施变更只能依赖上层间接覆盖。

## 5. RAG Trace

Trace 有两层表：

- `t_rag_trace_run`：一次聊天运行；
- `t_rag_trace_node`：树形阶段节点。

[StreamChatTraceRunner](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/trace/StreamChatTraceRunner.java) 创建 run、记录完整链路 TTFT，并在 callback 终态收尾。[RagTraceAspect](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/aop/RagTraceAspect.java) 处理普通 `@RagTraceNode`，[RagStreamTraceSupportImpl](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/trace/RagStreamTraceSupportImpl.java) 处理跨线程流。

典型节点：

| 类型 | 代表阶段 |
| --- | --- |
| `REWRITE` | 查询改写与拆分 |
| `INTENT` | 意图解析 |
| `RETRIEVE` | 检索编排 |
| `RETRIEVE_CHANNEL` | 多通道检索 |
| `LLM_ROUTING` | 模型候选选择与调用 |
| `TITLE_GEN` | 会话标题 |
| `RECOMMEND_GEN` | 推荐追问 |
| `USER_TTFT` | 从 Pipeline 入口到用户第一个内容 |
| `STREAM` | 跨线程模型流 |

管理前端可按 Trace 查看运行状态、树节点和耗时。

## 6. 其他可观测数据

| 数据 | 位置 | 用途 |
| --- | --- | --- |
| 文档分块日志 | `t_knowledge_document_chunk_log` | 提取、分块、Embedding、持久化耗时 |
| 定时刷新执行 | `t_knowledge_document_schedule_exec` | 变化检测、成功/失败/跳过 |
| 摄取任务/节点 | `t_ingestion_task*` | 节点顺序、输出、耗时和错误 |
| 业务变更审计 | `t_biz_change_log` | 管理操作前后快照与操作人 |
| 检索归因日志 | 应用日志 | 各通道进入/存活 Rerank 的候选数 |
| 模型路由日志 | 应用日志 | 候选、Provider、失败和切换 |
| MQ 日志 | Producer/Consumer | 发送、事务回查、消费 |

Trace 面向 RAG 性能和异常；业务审计面向“谁改了什么”；分块/摄取日志面向离线任务。三者不能互相替代。

## 7. 按症状排障

### 7.1 请求一直无首包

依次查看：

1. 全局限流是否仍在排队；
2. Trace 是否停在 memory、rewrite、intent 或 retrieval；
3. 检索通道是否在等待外部网络；
4. 模型候选是否逐个等待首包超时；
5. `modelStreamExecutor` 是否拒绝；
6. SSE 是否已在客户端/网关超时。

### 7.2 有检索结果但回答“未检索到”

检查：

- `RetrievalEngine` 是否因子问题异常返回空上下文；
- Chunk 是否在后处理被全部去重/截断/Rerank 掉；
- `ContextFormatter` 是否过滤掉未命中意图；
- KB/MCP context 是否为空白；
- 搜索配置预算是否符合预期。

### 7.3 文档长期 running

检查：

- MQ Consumer 是否启动；
- 文档 Chunk 日志最后阶段；
- MinerU 轮询是否结束；
- Embedding 和向量维度；
- `KnowledgeDocumentScheduleJob.recoverStuckRunningDocuments`；
- 对象存储与派生索引错误。

### 7.4 用户点击停止但仍有内容

检查：

- 前端是否已收到 `meta` 并获得 `taskId`；
- stop 请求是否成功；
- Redis cancel bucket/topic；
- 当前应用实例本地任务 Cache；
- 模型 Client 是否真正取消 OkHttp Call；
- 晚到回调是否被 `taskManager.isCancelled` 丢弃。

## 8. 日志与隐私

当前 Trace 会保存原始问题，意图日志也会输出问题和分类树，MCP Client 日志会输出参数 Map。开发环境有助于学习，生产环境可能包含个人信息、内部数据或工具敏感参数。

应把日志/Trace 数据分类、掩码、访问控制和保留期作为可观测性设计的一部分，而不是只调日志级别。

## 9. 推荐的质量门槛

在不改变业务设计的前提下，合理的后续质量门槛包括：

- 后端单元测试与外部集成测试分 profile；
- `framework`、`infra-ai` 各自拥有直接测试；
- 前端 Store/SSE parser 的 Vitest 测试；
- API 权限集成测试；
- Prompt JSON 任务的固定样例；
- 关键 RAG 问题集的离线评测；
- 构建只检查格式，不自动改写源文件；
- CI 检查未提交生成物、秘密和破损文档链接。
