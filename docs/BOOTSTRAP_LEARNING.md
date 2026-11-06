# bootstrap 模块学习文档

> 本文档面向第一次阅读 Ragent Monster 后端的开发者，目标是回答三个问题：`bootstrap` 模块负责什么、一次请求如何流转、修改某个能力时应该从哪里下手。
>
> 文档依据当前代码编写。源码路径均以 `bootstrap/src/main/...` 为基准；如果配置或实现发生变化，应以代码为准。

## 1. 模块定位

`bootstrap` 是 Ragent Monster 的可执行 Spring Boot 主应用，也是业务编排最集中的模块。它把 `framework` 的通用能力、`infra-ai` 的模型访问能力，以及本模块的知识库/RAG 领域代码组装成一个 HTTP 服务。

从 Maven 依赖看，模块直接依赖：

- `framework`：统一返回体、异常、用户上下文、幂等、MQ 抽象等；
- `infra-ai`：聊天、Embedding、Rerank、VLM 和模型路由；
- Spring Web/Actuator/JDBC/Validation；
- PostgreSQL + pgvector、Milvus、Elasticsearch；
- Apache Tika、CommonMark、POI 相关解析能力；
- S3/OSS 对象存储、RocketMQ、MCP SDK、BizLog。

模块的主要职责可以概括为：

1. 暴露 REST 与 SSE API；
2. 管理知识库、文档、Chunk、会话和用户；
3. 执行文档摄取（Ingestion）流水线；
4. 编排 Agentic RAG 问答；
5. 将向量、关键词和图谱等检索投影异步同步到外部后端；
6. 提供鉴权、限流、审计、Trace、定时任务和运维接口。

它不是一个纯粹的“启动壳”：领域服务、解析器、检索算法和大量持久化逻辑都在这里；真正的启动入口只有一个。

## 2. 启动入口与 Spring 装配

入口类是 `com.hjs.study.ragent.RagentApplication`：

```java
@SpringBootApplication
@EnableScheduling
@EnableLogRecord(tenant = "ragent", proxyTargetClass = true)
@MapperScan({
    "com.hjs.study.ragent.rag.dao.mapper",
    "com.hjs.study.ragent.rag.projection.dao.mapper",
    "com.hjs.study.ragent.ingestion.dao.mapper",
    "com.hjs.study.ragent.knowledge.dao.mapper",
    "com.hjs.study.ragent.user.dao.mapper",
    "com.hjs.study.ragent.audit.dao.mapper"
})
public class RagentApplication { ... }
```

启动时会发生以下事情：

1. Spring Boot 扫描 `com.hjs.study.ragent` 下的 Controller、Service、Component 和 Configuration；
2. MyBatis-Plus 扫描六组 Mapper 接口；
3. `@EnableScheduling` 激活文档定时刷新、卡死恢复、ES/投影补偿扫描等任务；
4. BizLog 注解切面开始记录知识库、文档、Chunk 等变更审计；
5. `application.yaml` 绑定 `@ConfigurationProperties`；
6. 向量空间、Redis 信号量、对象存储客户端等初始化 Bean 被创建；
7. 环境后置处理器在 Bean 创建前校验检索通道配置。

### 2.1 启动前置校验

`rag.config.validation.RetrievalConfigEnvironmentPostProcessor` 通过 `META-INF/spring.factories` 注册。它在配置文件加载后、Bean 创建前运行，调用 `RetrievalChannelConfigValidator` 检查：

- `rag.keyword.type` 不是 `es` 时，不能把 `rag.search.channels.keyword.enabled` 设为 `true`；
- `rag.graph.type` 不是 `lightrag` 时，不能把 `rag.search.channels.graph.enabled` 设为 `true`。

这样可以在启动阶段直接暴露“通道开关打开但后端根本未装配”的错误，而不是运行到请求时才静默无结果。`MemoryConfigValidator` 还会保证摘要触发轮数大于保留轮数。

### 2.2 条件化装配

可选后端大量使用 `@ConditionalOnProperty`：

| 能力 | Bean 装配条件 |
| --- | --- |
| pgVector | `rag.vector.type=pg` |
| Milvus | `rag.vector.type=milvus`（部分类缺省按 Milvus） |
| Elasticsearch | `rag.keyword.type=es` |
| LightRAG | `rag.graph.type=lightrag` |
| S3/RustFS | `rag.storage.type=s3` |
| 阿里云 OSS | `rag.storage.type=oss` |
| 评测接口 | `app.eval.enabled=true` |

注意：后端“装配”和检索通道“参与”是两层开关。例如可以装配 LightRAG 供图谱可视化，但把 `rag.search.channels.graph.enabled` 设为 `false`，此时它不参与问答召回。

## 3. 目录地图

```text
com.hjs.study.ragent
├─ RagentApplication.java       启动入口
├─ admin/                       仪表盘
├─ audit/                       业务变更审计
├─ core/                        文档解析与分块的框架无关核心
├─ ingestion/                   可配置文档摄取流水线
├─ knowledge/                   知识库/文档/Chunk 业务与异步任务
├─ rag/                         问答、检索、记忆、投影、Trace、配置
└─ user/                        登录、用户、Sa-Token 集成
```

各层的典型依赖方向是：

```text
Controller → Service → Domain/Core → Mapper / 外部客户端
                         ├─ framework
                         └─ infra-ai
```

Controller 只负责参数接收和统一返回；复杂编排放在 Service 或 Engine；外部系统通过接口隔离，具体实现由配置选择。

## 4. 两条主业务链路

### 4.1 文档入库链路

```text
上传文件/URL
  → FileStorageService 写入 S3/RustFS/OSS
  → DocumentParserSelector 按 MIME 选择解析器
  → ParsedDocument（Block 列表）
  → ChunkingStrategyFactory / StructuredChunkingService 分块
  → ChunkEmbeddingService 调用 infra-ai Embedding
  → VectorStoreService 写入 pgVector 或 Milvus
  → PostgreSQL 保存文档、Chunk、处理日志
  → ProjectionCoordinator 触发 ES/LightRAG 异步投影
```

知识库默认通过 `KnowledgeDocumentController` 的上传接口完成这条链路。上传本身先落文档记录并保存文件，`POST /knowledge-base/docs/{doc-id}/chunk` 再通过 RocketMQ 异步触发真正的解析、分块和向量化。

### 4.2 问答链路

```text
GET /rag/v3/chat
  → Sa-Token 登录校验 + UserContext
  → RAGChatServiceImpl 创建 conversationId/taskId
  → ChatQueueLimiter 分布式排队
  → StreamChatTraceRunner 创建 Trace
  → StreamChatPipeline
       记忆加载/追加
       → 问题改写与拆分
       → 意图树解析
       → 歧义引导或系统问答短路
       → MCP 工具调用 + KB 多通道检索
       → 去重 → 版本/元数据处理 → RRF → Rerank
       → Prompt 规划与模板渲染
       → infra-ai 流式 LLM
  → SSE 输出内容、来源、grounding、完成/错误事件
  → 消息、Trace、推荐问题和反馈落库
```

## 5. 文档解析与分块（`core`）

### 5.1 统一解析器接口

`core.parser.DocumentParser` 是解析 SPI：

- `getParserType()`：返回 `Tika`、`Markdown`、`ExcelPoi`、`Csv`、`MinerU`、`Image` 等标识；
- `parseStructured(byte[], mimeType, options)`：返回 `ParsedDocument`；
- `supports(mimeType)`：声明 MIME 支持范围。

`DocumentParserSelector` 收集所有解析器 Bean，支持两种选择方式：

```java
select("Markdown");          // 按解析器类型
selectByMimeType("text/csv"); // 按 MIME 自动路由
```

没有匹配解析器时返回 `null`，由 `ParserNode` 或上传前置校验显式报错；不会静默把未知文件交给 Tika。

### 5.2 解析器分工

| 解析器 | 适用内容 | 输出特点 |
| --- | --- | --- |
| `MarkdownDocumentParser` | Markdown、纯文本 | 使用 CommonMark AST 输出标题、段落、代码、列表、GFM 表格 Block |
| `ExcelDocumentParser` | XLS/XLSX | Apache POI，处理合并单元格、多行表头、公式、超链接 |
| `CsvDocumentParser` | CSV | 字符集/RFC4180 处理，产出表格 Block |
| `TikaDocumentParser` | `text/*`、JSON/XML/RTF 等基础文本 | 清洗后按空行产出 ParagraphBlock |
| `MinerUDocumentParser` | PDF、Word、PPT 等复杂版面 | 调 MinerU SaaS，轮询任务并解包图片/表格等资产 |
| `ImageDocumentParser` | PNG/JPG 等图片 | 调 VLM 生成图生文，同时保留原图资产引用 |

结构化模型位于 `core.parser.model`：`HeadingBlock`、`ParagraphBlock`、`CodeBlock`、`ListBlock`、`TableBlock`、`ImageBlock` 和 `Provenance`。解析器只负责理解文档，不负责向量化。

### 5.3 分块策略

`ChunkingStrategy` 是分块 SPI，`ChunkingStrategyFactory` 在 `@PostConstruct` 阶段收集并校验每个 `ChunkingMode` 只有一个实现。当前可见策略：

1. `FIXED_SIZE`：按字符数切分，支持 `chunkSize` 和 `overlapSize`，在换行/中文句末/英文句末边界回退；会修复 URL 断行，但避免吞掉列表项。
2. `STRUCTURE_AWARE`：先扫描标题、段落、代码围栏、图片/链接原子行，再按 `minChars/targetChars/maxChars` 在 Block 边界打包；尽量不改写原文。

`StructuredChunkingService` 负责把 `ParsedDocument` 的 Block 转为 `VectorChunk`，并把来源、资产和结构元数据写入 Chunk。新增策略时通常需要：

```text
新增 ChunkingMode
  → 实现 ChunkingStrategy
  → 调整配置/默认参数
  → 增加策略单测
```

## 6. 可配置 Ingestion 流水线（`ingestion`）

### 6.1 数据模型

- `PipelineDefinition`：流水线定义及节点列表；
- `NodeConfig`：节点 ID、节点类型、下一节点 ID、JSON 条件和节点配置；
- `IngestionContext`：在节点间传递的可变上下文，包含原始字节、MIME、文本、结构化文档、Chunks、Embedding、元数据、状态和日志；
- `NodeResult` / `NodeLog`：节点结果和可观测记录。

### 6.2 内置节点

| 节点 | 作用 |
| --- | --- |
| `FetcherNode` | 从上传内容、HTTP URL 或飞书等来源取得字节和 MIME |
| `ParserNode` | 根据 MIME/规则调用 `DocumentParser` |
| `EnhancerNode` | 使用 LLM 对整篇文本做摘要、清洗或上下文增强 |
| `ChunkerNode` | 按策略切分并生成 Embedding |
| `EnricherNode` | 对每个 Chunk 生成关键词、问题或其他元数据 |
| `IndexerNode` | 校验向量维度并写入向量存储；可由调用方设置 `skipIndexerWrite` |

### 6.3 引擎执行规则

`IngestionEngine.execute()` 会：

1. 根据 `nodeId` 建立配置映射；
2. 校验下一节点存在、链路无环；
3. 找到“没有被其他节点引用”的起始节点；
4. 逐节点执行，记录耗时、输出快照和错误；
5. 条件不满足时记录 `skip` 并继续链路；
6. 节点失败将上下文置为 `FAILED`，成功走到末尾则置为 `COMPLETED`。

因此当前流水线是“单链 nextNodeId”模型，不是通用 DAG。修改节点连线时要重点检查环、孤儿节点和错误的节点类型。

### 6.4 任务入口

`IngestionTaskServiceImpl` 创建任务记录，读取流水线定义，构建 `IngestionContext`，调用引擎，然后把节点日志写入 `t_ingestion_task_node`。它既支持 JSON/URL 来源，也支持 `multipart/form-data` 文件上传。

## 7. 知识库领域（`knowledge`）

### 7.1 领域对象与状态

核心表/实体：

| 实体 | 表 | 关键字段 |
| --- | --- | --- |
| `KnowledgeBaseDO` | `t_knowledge_base` | 名称、Embedding 模型、向量集合名 |
| `KnowledgeDocumentDO` | `t_knowledge_document` | 来源、文件、处理模式、Chunk 策略、状态、内容版本 |
| `KnowledgeChunkDO` | `t_knowledge_chunk` | 文本、索引、Hash、启用状态、Token/字符数 |
| `KnowledgeDocumentChunkLogDO` | `t_knowledge_document_chunk_log` | 每次分块执行的耗时与错误 |
| `KnowledgeDocumentScheduleDO` | `t_knowledge_document_schedule` | URL/飞书等定时刷新计划 |

文档状态为 `pending → running → success/failed`。启用/禁用文档或 Chunk 会同步影响向量索引。

### 7.2 上传和分块

`KnowledgeDocumentServiceImpl.upload()` 的关键步骤：


- 校验知识库存在和来源/定时配置；
- 调用文件存储服务，得到 URL、MIME、大小和文件名；
- 用 `DocumentParserSelector` 预检 MIME，未知格式立即删除已存文件并报错；
- 保存 `KnowledgeDocumentDO`，默认 `PENDING`；
- 根据 `processMode` 选择传统 Chunk 模式或自定义 Pipeline 模式。

`startChunk()` 通过 RocketMQ 事务消息把文档状态更新为 `RUNNING`，并发送 `KnowledgeDocumentChunkEvent`。消费者 `KnowledgeDocumentChunkConsumer` 调用 `executeChunk()`，最终完成解析、分块、Embedding、向量写入和 Chunk 日志。

### 7.3 定时刷新与限流

- `UploadRateLimitFilter` 在 multipart 解析前使用 Redis 可过期信号量，拿不到许可返回 HTTP 429；
- `KnowledgeDocumentScheduleJob` 定时扫描到期计划，使用 `ScheduleLockManager` 抢锁后异步刷新；
- 卡住的 `RUNNING` 文档会由恢复任务重新标记或重试；
- `KnowledgeScheduleProperties` 控制扫描间隔、锁时长、批量大小和最小刷新间隔。

## 8. RAG 问答核心（`rag`）

### 8.1 SSE 入口与任务控制

`RAGChatController` 暴露：

- `GET /rag/v3/chat?question=...&conversationId=...&deepThinking=false`：创建 `SseEmitter` 并启动流式问答；
- `POST /rag/v3/stop?taskId=...`：取消指定任务。

`RAGChatServiceImpl` 为每次请求生成 conversationId（缺省时）和 taskId，先进入 `ChatQueueLimiter`，再由 `StreamChatTraceRunner` 包装 Trace，最后调用 `StreamChatPipeline`。`StreamTaskManager` 保存 taskId 到底层 `StreamCancellationHandle` 的映射。

### 8.2 `StreamChatPipeline` 阶段

源码中的执行顺序是固定的：

1. `loadMemory`：并行读取摘要和历史消息，追加用户问题并获得 replyToMessageId；
2. `rewriteQuery`：`MultiQuestionRewriteService` 使用 Prompt + LLM 改写问题并拆分子问题，失败时有规则兜底；
3. `resolveIntents`：`IntentResolver` 对每个子问题调用意图分类器，控制每个子问题和总意图数；
4. `handleGuidance`：`IntentGuidanceService` 判断多个相近意图是否需要向用户澄清；
5. `handleSystemOnly`：如果全部意图都是系统问答，直接用系统 Prompt 生成回答，不做知识库检索；
6. `retrieve`：对每个子问题并行构建 `RetrievalContext`；
7. `handleEmptyRetrieval`：无 MCP、无 KB 上下文时返回“未检索到相关文档”；
8. `streamRagResponse`：发送来源和 grounding，组装结构化 Prompt，调用流式 LLM。

### 8.3 意图树

`IntentNode` 支持三类意图：

- `KB`：指向知识库/向量集合；
- `MCP`：指向 MCP 工具和参数 Prompt；
- `SYSTEM`：只使用系统 Prompt 的问答。

`DefaultIntentClassifier` 从 `t_intent_node` 加载树并缓存，构造包含叶子节点、描述和示例的分类 Prompt，解析模型返回的分数。`IntentResolver` 再按分数过滤和限额，生成 `SubQuestionIntent`。

### 8.4 多通道检索

`RetrievalEngine` 将 KB 与 MCP 分开处理：

- MCP 意图：`McpParameterExtractor` 提取参数，只有 `SUCCESS` 才真正调用工具；缺必填参数会生成澄清提示，不会编造参数调用远端工具；
- KB 意图：调用 `MultiChannelRetrievalEngine`。

`MultiChannelRetrievalEngine` 的流程：

```text
过滤启用通道
  → CompletableFuture 并行 search
  → 合并所有 RetrievedChunk
  → 按 order 执行后置处理器
```

内置通道：

- `VectorSearchChannel`：意图定向检索或低置信度时全局检索；支持 pgVector/Milvus；
- `KeywordSearchChannel`：仅 ES 后端装配且通道打开时生效；
- `GraphSearchChannel`：仅 LightRAG 后端装配且通道打开时生效；
- `WebSearchChannel`：需要开启开关并配置 YDC API Key。

后置处理器通常按以下语义组合：去重、元数据/版本处理、RRF 融合、Rerank。`RetrievalBudget` 约束三段漏斗：每通道召回数、Rerank 候选上限、最终进入 Prompt 的 `default-top-k`。

RRF 的基本思想是把不同通道的名次转成可相加的分数：

```text
score(chunk) += channelWeight / (rrfK + rank)
```

这样不要求向量分数、BM25 分数和图谱分数处于同一量纲。

### 8.5 Prompt 与模型路由

Prompt 模板位于 `src/main/resources/prompt`，由 `PromptTemplateLoader` 缓存并替换 `{{slot}}`。`RAGPromptService` 根据 KB-only、MCP-only、mixed 三种场景规划 system/user 消息，并将来源上下文、子问题和意图信息格式化。

`infra-ai` 的 `RoutingLLMService` 屏蔽供应商差异：

- `ModelSelector` 根据 tier、thinking、preferred model 选择候选；
- `ModelRoutingExecutor` 按候选顺序失败回退；
- `ModelHealthStore` 对连续失败模型熔断，经过开放窗口后半开探测；
- 流式调用使用首包探针，首包超时/无内容时尝试下一个模型；
- `StreamCallback` 统一承载 content、thinking、sources、grounding、complete、error。

## 9. 向量、对象存储与异步投影

### 9.1 向量存储抽象

`VectorStoreService` / `VectorRetrieverService` 隔离向量数据库：

- pgVector 实现使用 `t_knowledge_vector`，通过 JDBC 和 HNSW；
- Milvus 实现使用 Milvus Java SDK；
- `VectorSpaceInitializer` 在启动时确保默认集合/表存在；
- `VectorSpaceId` 和 `VectorSpaceSpec` 统一描述逻辑空间、维度和距离度量。

Embedding 维度必须与 `rag.default.dimension`、数据库 `vector(1536)`（默认）以及所选模型一致。维度不匹配是最常见的入库失败原因之一。

### 9.2 对象存储抽象

`ObjectStorageClient` 只暴露 bucket/key 层面的裸操作：上传、读取、删除、前缀清理、Bucket 初始化和公共 URL 构造。`S3ObjectStorageClient` 服务 RustFS/MinIO 等 S3 兼容实现，`OssObjectStorageClient` 服务阿里云 OSS；`DefaultFileStorageService` 再负责知识库 namespace、文件 URL 与业务 DTO。

默认桶：

- `ragent-sources`：私有源文件；
- `ragent-assets`：图片等多模态资产，默认公共读以便浏览器预览。

### 9.3 ES/LightRAG 投影

向量写入 PostgreSQL/Milvus 是主数据链路；ES 和 LightRAG 属于异步检索投影。`ProjectionCoordinator` 在文档、Chunk、知识库变更后合并目标状态并发送唤醒消息；`ProjectionWorkerScheduler` 定时补偿扫描。

`ProjectionJobExecutor` 负责租约、并发槽位、心跳续租、版本 CAS、重试退避和执行审计。每个目标由一个 `ProjectionProcessor` 实现：

- `EsProjectionProcessor`：按文档内容版本写入新版本，成功后删除旧版本；
- `LightRagProjectionProcessor`：每个 workspace 串行、整篇提交，轮询外部 track 状态，完成后更新外部文档 ID。

投影状态存放在 `t_rag_projection_state`，执行尝试存放在 `t_rag_projection_attempt`。版本号和 CAS 保证“旧任务不会覆盖新内容”。

## 10. 鉴权、统一响应与横切能力

### 10.1 鉴权和用户上下文

`SaTokenConfig` 注册三个拦截器：

1. Sa-Token 登录检查，排除 `/auth/**`、`/error`，并放行 OPTIONS/异步 SSE dispatch；
2. `DemoModeInterceptor`：`app.demo-mode=true` 时禁止写操作，但保留查询；
3. `UserContextInterceptor`：从 Token 查询用户，写入 TTL `UserContext`，请求完成后清理。

任何异步线程池若需要用户信息，都必须使用已包装的上下文传递执行器，并在任务结束后确认上下文清理。

### 10.2 统一返回体

普通 Controller 返回 `Result<T>`，成功通过 `Results.success(data)` 构造，成功码固定为字符串 `"0"`。异常由框架异常处理器统一转为同样的 `Result` 结构，字段包括 `code`、`message`、`data` 和 `requestId`。

SSE 不使用 `Result<T>`，而是通过事件发送增量消息；客户端应处理内容、思考、来源、推荐问题、完成和错误等事件。

### 10.3 Trace、审计与指标

- `@RagTraceNode` 标注的方法由 `RagTraceAspect` 记录节点开始、成功/失败、耗时和父子层级；
- `RagTraceContext` 保存当前 traceId/nodeId；
- `@LogRecord` 与 `BizChangeLogRecordService` 记录知识库、文档和 Chunk 变更前后快照；
- Actuator 暴露 `health`、`info`、`prometheus`；
- 投影、模型路由和限流器均输出结构化日志与 Micrometer 指标。

## 11. 主要 API 速查

所有路径都带 `server.servlet.context-path=/api/ragent`，因此完整地址前缀是 `http://127.0.0.1:9090/api/ragent`。

| 控制器 | 主要接口 |
| --- | --- |
| `AuthController` | `POST /auth/login`、`POST /auth/logout` |
| `UserController` | `GET /user/me`、`GET/POST/PUT/DELETE /users...`、`PUT /user/password` |
| `KnowledgeBaseController` | `POST/PUT/DELETE/GET /knowledge-base...`、`GET /knowledge-base/chunk-strategies` |
| `KnowledgeDocumentController` | 上传、分块、详情、分页、搜索、启停、预览、源文件 |
| `KnowledgeChunkController` | Chunk 分页、新增、更新、删除、单条/批量启停 |
| `IngestionPipelineController` | 流水线增删改查 |
| `IngestionTaskController` | 任务执行、文件上传、详情、节点日志、分页 |
| `RAGChatController` | `GET /rag/v3/chat`、`POST /rag/v3/stop` |
| `ConversationController` | 会话列表、更新、删除、消息列表 |
| `IntentTreeController` | 意图树查询、增删改、批量启停/删除 |
| `GraphController` / 投影管理 | 图谱视图、workspace、投影摘要/重建/重试 |
| `RagTraceController` | Trace 运行、详情、节点 |
| `DashboardController` | `/admin/dashboard/overview|performance|trends` |

## 12. 配置重点

### 12.1 本地默认组合

仓库默认推荐：

```yaml
rag:
  storage:
    type: s3
  vector:
    type: pg
  keyword:
    type: none
  graph:
    type: none
  search:
    channels:
      vector:
        enabled: true
      keyword:
        enabled: false
      graph:
        enabled: false
```

对应 PostgreSQL、Redis、RocketMQ、RustFS 四类基础设施即可跑通主链路；Milvus、ES、LightRAG、Web Search 都是可选项。

### 12.2 最常改的配置

| 配置前缀 | 用途 |
| --- | --- |
| `server.*` | 端口和上下文路径（默认 9090、`/api/ragent`） |
| `spring.datasource.*` | PostgreSQL/Hikari |
| `spring.data.redis.*` | Redis 地址和 DB 15 |
| `rocketmq.*` | NameServer、生产者组 |
| `rag.storage.*` | S3/OSS 桶和凭据 |
| `rag.vector.*` / `rag.default.*` | 向量后端、集合名、维度、距离度量 |
| `rag.search.*` | 通道开关、召回预算、RRF、Rerank |
| `rag.memory.*` | 历史轮数、摘要触发和长度 |
| `ai.*` | Provider、模型候选、tier、熔断和流式参数 |
| `mineru.*` | MinerU API、轮询、OCR、并发限制 |
| `rag.projection.*` | 投影 worker 租约、扫描和重试 |

敏感值应使用环境变量，例如 `BAILIAN_API_KEY`、`DEEPSEEK_API_KEY`、`OSS_ACCESS_KEY`、`MINERU_API_KEY`，不要提交到 YAML。

## 13. 源码阅读路线

建议按以下顺序学习，每一步都能运行或单测验证：

1. `RagentApplication` + `application.yaml`：理解 Bean 扫描、条件装配和基础设施；
2. `RAGChatController` → `RAGChatServiceImpl` → `StreamChatPipeline`：先掌握最有代表性的问答主链路；
3. `RetrievalEngine` → `MultiChannelRetrievalEngine` → 各 `SearchChannel`/`PostProcessor`：理解 RAG 检索漏斗；
4. `DocumentParser` → `DocumentParserSelector` → Markdown/Excel/Tika/MinerU：理解多格式解析；
5. `ChunkingStrategy` → `StructuredChunkingService`：理解 Chunk 质量如何影响召回；
6. `KnowledgeDocumentServiceImpl` → `KnowledgeDocumentChunkConsumer`：理解异步文档入库；
7. `VectorStoreService`、`ObjectStorageClient`：理解存储抽象和可选后端；
8. `ProjectionCoordinator` → `ProjectionJobExecutor`：理解 ES/图谱最终一致性；
9. `SaTokenConfig`、`UserContextInterceptor`、`RagTraceAspect`：补齐横切能力；
10. 最后阅读 `admin`、`audit`、`eval` 等外围模块。

每读完一层，建议配合对应测试：

- `core/chunk/blockaware/ChunkPackerTest`、`rag/config/SearchChannelPropertiesTest`；
- `rag/rewrite/MultiQuestionRewriteServiceTests`、`rag/core/intent/IntentResolverTest`；
- `rag/core/retrieval/postprocessor/DeduplicationPostProcessorTest`；
- `rag/projection/ProjectionFreshnessGateTest`、`ProjectionAdminServiceTest`；
- `schedule/ScheduleRefreshProcessorTest`、`service/ConversationMessageServiceTests`。

## 14. 常见扩展任务

### 新增一种文档格式

实现 `DocumentParser`，声明 MIME 支持，返回 `ParsedDocument`；如果需要特殊资产处理，再扩展 `AssetRef`/对应 Block。最后在 `DocumentParserSelector` 的选择顺序和上传 MIME 预检中验证。

### 新增一种分块策略

增加 `ChunkingMode`、实现 `ChunkingStrategy`、补默认配置和单测。不要在 Service 中写 `if/else` 选择具体实现，使用 `ChunkingStrategyFactory`。

### 新增检索通道

实现 `SearchChannel`，必要时用 `@ConditionalOnProperty` 控制后端 Bean；在 `SearchChannelProperties` 增加配置和校验；通过 `SearchResultPostProcessor` 处理统一的去重、融合和排序。

### 新增模型供应商

在 `infra-ai` 实现对应 `ChatClient`/`EmbeddingClient`/`RerankClient`/`VlmService`，在 `ai.providers` 配置 URL 和 endpoint，再把候选模型加入 `ai.*.candidates`。业务层不应直接依赖供应商 SDK。

### 修改文档写入逻辑

同时检查 PostgreSQL 主数据、向量存储和 ES/LightRAG 投影。主库成功不代表所有投影立即成功；应通过 `ProjectionCoordinator` 触发版本化异步同步，不能在 Controller 中直接调用外部索引 SDK。

## 15. 调试与排障清单

1. 启动失败：先看检索配置校验、数据库连接、Redis、RocketMQ；
2. 上传 429：检查 `rag.semaphore.document-upload` 和 Redis；
3. 文档卡在 `running`：查看 `t_knowledge_document_chunk_log`、RocketMQ 消费日志和恢复任务；
4. 没有召回：确认文档/Chunk `enabled=1`、向量维度一致、`rag.search.channels.vector.enabled=true`；
5. ES/图谱无结果：确认后端 `type` 和通道 `enabled` 同时打开，并查看投影状态表；
6. SSE 无输出：检查模型 API Key、模型候选是否全部熔断、首包超时和 `taskId`；
7. 用户串号：检查异步执行器是否传递/清理 `UserContext`；
8. Trace 不完整：确认 `rag.trace.enabled=true`，并检查 `@RagTraceNode` 方法是否经过 Spring 代理调用；
9. 中文乱码：确认 `WebConfig` 的 UTF-8 转换器和 SSE `text/event-stream;charset=UTF-8`；
10. 生产环境：替换默认密码、S3 凭据、Token 配置、CORS `*` 和所有本地端口。

## 16. 最小验证流程

```powershell
# 在仓库根目录
.\mvnw.cmd -DskipTests package

# 启动 MCP（可选）
java -jar mcp-server/target/mcp-server-1.0-SNAPSHOT.jar

# 启动主服务
java -jar bootstrap/target/bootstrap-1.0-SNAPSHOT.jar
```

然后按以下顺序验证：

1. `POST /api/ragent/auth/login` 获取 Token；
2. `GET /api/ragent/user/me` 验证鉴权和用户上下文；
3. 创建知识库并上传一个 Markdown/TXT 文件；
4. 调用文档分块接口，确认状态变为 `success` 且 Chunk 数大于 0；
5. 调用 `GET /api/ragent/rag/v3/chat?question=...`，观察 SSE 内容、来源和完成事件；
6. 在管理端查看 Trace，确认 `query-rewrite`、`intent-resolve`、`retrieval-engine`、`llm-routing` 节点均有记录。

## 17. 一句话总结

学习 `bootstrap` 的关键不是记住 492 个 Java 文件，而是抓住四个可替换的管道：

```text
文档：Fetcher → Parser → Chunker → Embed → Index
问答：Memory → Rewrite → Intent → Retrieve → Prompt → LLM
投影：Desired State → Lease Worker → Version CAS → External Index
横切：Auth → Context → Rate Limit → Trace → Audit
```

沿着这四条线阅读，你可以快速定位任何功能的入口、状态变化、外部依赖和测试位置。
