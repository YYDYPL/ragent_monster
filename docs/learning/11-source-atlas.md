# 源码地图

## 1. 覆盖说明

本地图用于确认一方源码均有归属。统计基线为提交 `4457305`：

| 根目录/文件 | 已跟踪数量 | 在文档中的定位 |
| --- | ---: | --- |
| `bootstrap` | 505 | 主业务、配置、资源和测试 |
| `frontend` | 136（其中 TS/TSX 118） | React 应用与构建配置 |
| `infra-ai` | 53 | 模型基础设施 |
| `framework` | 41 | 通用基础设施 |
| `resources` | 31 | SQL、Compose、格式和示例知识 |
| `mcp-server` | 10 | 独立 MCP 示例服务 |
| `docs` | 原有 7 | 使用、示例与版本文档；本目录为新增 |
| `scripts` | 1 | SSE 排队压测脚本 |
| 根文件 | 若干 | Maven、License、Git、Lombok |

计数可能因本学习文档加入而变化。这里的覆盖对象是实施前已跟踪基线，不包含 `node_modules`、`.idea`、构建产物或未跟踪文件。

## 2. bootstrap 总览

`bootstrap/src/main/java/com/hjs/study/ragent` 有 7 个一级业务域：

| 包 | 生产 Java 数 | 角色 |
| --- | ---: | --- |
| `rag` | 229 | 在线问答、会话、检索、意图、MCP、Prompt、Trace |
| `knowledge` | 68 | 知识库、文档、Chunk、调度和 MQ |
| `ingestion` | 62 | 可配置摄取流水线 |
| `core` | 56 | 解析、结构化文档、分块、Embedding |
| `user` | 20 | 登录、用户、角色、上下文 |
| `audit` | 12 | 业务变更日志 |
| `admin` | 10 | Dashboard |

### 2.1 应用入口

- [RagentApplication](../../bootstrap/src/main/java/com/hjs/study/ragent/RagentApplication.java)：主 Spring Boot 入口。
- `application.yaml`：全部主服务默认配置。
- `META-INF/spring.factories`：环境后处理/自动配置注册。
- `additional-spring-configuration-metadata.json`：IDE 配置提示。

## 3. rag 包

### 3.1 controller

10 个控制器及其 request/vo：

- `RAGChatController`
- `ConversationController`
- `MessageFeedbackController`
- `RecommendedQuestionController`
- `SampleQuestionController`
- `IntentTreeController`
- `QueryTermMappingController`
- `RagTraceController`
- `RAGSettingsController`
- `GraphController`

完整路径见 [API 文档](09-api-config-and-prompts.md)。

### 3.2 service

接口层覆盖聊天、会话、消息、反馈、示例问题、推荐问题、Trace、Query Mapping 和文件存储。

关键实现：

| 类型 | 责任 |
| --- | --- |
| `RAGChatServiceImpl` | 创建会话/任务/回调并进入队列 |
| `StreamChatPipeline` | 聊天主编排 |
| `StreamChatEventHandler` | SSE、答案累积与落库 |
| `StreamTaskManager` | 跨实例取消 |
| `ChatQueueLimiter` / `FairDistributedRateLimiter` | 全局公平限流 |
| `ConversationServiceImpl` | 会话列表、标题、重命名、删除 |
| `ConversationMessageServiceImpl` | 消息查询与 VO |
| `RecommendedQuestionGenerator` | 基于问答和 Grounding 生成追问 |
| `RagTraceRecord/QueryServiceImpl` | Trace 写入与查询 |

### 3.3 rag/core

| 子包 | 文件数 | 核心类型 |
| --- | ---: | --- |
| `retrieval` | 19 | `RetrievalEngine`、`MultiChannelRetrievalEngine`、四类 Channel、PostProcessor |
| `vector` | 16 | pgvector/Milvus Store、Retriever、Admin、并行策略 |
| `intent` | 9 | 分类器、Resolver、树缓存、Node/Score |
| `mcp` | 9 | Client 自动配置、Registry、Executor、参数提取 |
| `prompt` | 9 | `RAGPromptService`、模板 Loader、ContextFormatter |
| `memory` | 6 | Memory Service/Store/Summary |
| `rewrite` | 6 | 改写、术语映射和缓存 |
| `keyword` | 4 | 关键词索引/检索接口与 ES 实现 |
| `guidance` | 3 | 歧义检查与引导 |
| `storage` | 3 | S3/OSS 对象存储 |
| `graph` | 2 | LightRAG 查询 |
| `source` | 2 | Sources 与 Grounding 装配 |

### 3.4 配置

`rag/config` 约 30 个类型，可按责任分组：

- 业务参数：`RAGDefaultProperties`、`RAGConfigProperties`、`MemoryProperties`；
- 检索：`SearchChannelProperties`、`KeywordProperties`、`GraphProperties`；
- 存储：`RagStorageProperties`、`StorageClientConfig`、`MilvusConfig`；
- 并发：`ThreadPoolExecutorConfig`、`RAGRateLimitProperties`；
- Web：`WebConfig`、`Utf8ResponseFilter`、`HttpClientConfig`；
- Trace：`RagTraceProperties`；
- Demo/Guidance：`DemoMode*`、`GuidanceProperties`；
- 启动校验：`config/validation`。

### 3.5 数据与其他

- `rag/dao/entity`：会话、消息、反馈、示例问题、意图、映射、Trace DO；
- `rag/dao/mapper`：相应 MyBatis Mapper；
- `rag/dto`：检索、意图、SSE、推荐问题 DTO/record；
- `rag/enums`：Intent、SSE 等枚举；
- `rag/aop`、`rag/trace`：Trace；
- `rag/mq`：消息反馈消费；
- `rag/eval`：条件注册的评测接口。
- `rag/constant`：`RAGConstant` 统一业务常量；
- `rag/util`：`FileTypeDetector` 负责文件类型判定。

## 4. knowledge 包

| 子包 | 关键内容 |
| --- | --- |
| `controller` | KnowledgeBase、Document、Chunk 三个控制器及 request/vo |
| `service` | 四个接口与 Base/Document/Chunk/Schedule 实现 |
| `dao` | 6 个 DO、6 个 Mapper、JSONB TypeHandler |
| `mq` | 文档分块、知识库清理事件、Consumer、TransactionChecker |
| `schedule` | 扫描、租约、心跳、刷新、状态、卡死恢复 |
| `handler` | `RemoteFileFetcher`，限大小下载与对象存储 |
| `filter/config` | 上传信号量和过滤器 |
| `enums` | 文档状态、处理模式、来源、调度状态 |

核心阅读顺序：

1. `KnowledgeDocumentController`
2. `KnowledgeDocumentServiceImpl`
3. `KnowledgeDocumentChunkConsumer`
4. `KnowledgeChunkServiceImpl`
5. `ScheduleRefreshProcessor`

## 5. ingestion 包

| 子包 | 核心类型 |
| --- | --- |
| `engine` | `IngestionEngine`、`ConditionEvaluator`、`NodeOutputExtractor` |
| `node` | `IngestionNode` 与六个节点实现 |
| `domain/context` | `IngestionContext`、`StructuredDocument`、`DocumentSource`、`NodeLog` |
| `domain/pipeline` | `PipelineDefinition`、`NodeConfig` |
| `domain/result` | `IngestionResult`、`NodeResult` |
| `domain/settings` | Parser/Chunker/Enhancer/Enricher/Indexer settings |
| `strategy/fetcher` | HTTP、飞书抓取 |
| `service` | Pipeline/Task/IntentTree 管理 |
| `dao` | Pipeline/Node/Task/TaskNode DO 与 Mapper |
| `controller` | Pipeline 与 Task API |
| `prompt/util` | 增强 Prompt、MIME、HTTP、JSON、模板渲染 |

`IntentTreeService` 放在 ingestion 包但服务于 RAG 意图管理，是一个包边界上的历史交叉点。

## 6. core 包

### 6.1 parser

| 类型组 | 内容 |
| --- | --- |
| 契约/选择 | `DocumentParser`、`DocumentParserSelector`、`ParserType`、`ParseResult` |
| 通用 | `TikaDocumentParser`、`TextCleanupUtil` |
| Markdown/CSV | `MarkdownDocumentParser`、`CsvDocumentParser` |
| Excel | Parser、HyperlinkResolver、Normalizer、ValueFormatter |
| Image | `ImageDocumentParser`、`ImageParseProperties` |
| MinerU | Client、Parser、PollingExecutor、ResultUnpacker、Properties、状态模型 |
| model | Block、Heading、Paragraph、List、Code、Table、Image、AssetRef、Provenance |

### 6.2 chunk

- 契约与配置：`ChunkingStrategy`、`ChunkingOptions`、`ChunkingMode`；
- 工厂与服务：`ChunkingStrategyFactory`、`StructuredChunkingService`；
- 策略：固定大小、结构感知；
- Block-aware：Dispatcher、各 Block Chunker、HeadingHandler、ChunkPacker、Renderer；
- 数据：`VectorChunk`、`ChunkContext`；
- Embedding：`ChunkEmbeddingService`。

## 7. user、audit、admin

### 7.1 user

- `AuthController/AuthServiceImpl`：登录退出；
- `UserController/UserServiceImpl`：当前用户、用户管理、密码修改；
- `SaTokenConfig`、`UserContextInterceptor`、`SaTokenStpInterfaceImpl`；
- User DO、Mapper、Role、请求/响应。

### 7.2 audit

- `BizChangeLogController/ServiceImpl`：查询；
- `BizChangeLogRecordService`：持久化；
- `RagentOperatorGetService`：操作人；
- `support/BizChangeLogContext` 与 `constant` 下的业务/操作类型常量；
- DO、Mapper、request/vo。

### 7.3 admin

- `DashboardController`
- `DashboardService/Impl`
- 7 个 Dashboard VO，聚合概览、性能和趋势。

## 8. framework

39 个生产类型按包分组：

| 包 | 类型 |
| --- | --- |
| `convention` | `Result`、`ChatRequest`、`ChatMessage`、`RetrievedChunk`、`SourceRef`、`GroundingChunk` |
| `web` | `Results`、`GlobalExceptionHandler`、`SseEmitterSender` |
| `context` | `ApplicationContextHolder`、`LoginUser`、`UserContext` |
| `exception/errorcode` | 四层异常与错误码 |
| `idempotent` | 提交/消费注解、切面、状态、SpEL |
| `mq` | MessageWrapper、Producer 适配、事务监听和 checker |
| `trace` | Trace 注解、上下文和跨线程 Stream 契约 |
| `config` | Web、数据库、RocketMQ 自动配置 |
| `database` | MyBatis 自动填充 |
| `distributedid` | Snowflake 与 MyBatis ID Generator |
| `cache` | Redis key serializer |
| `resources` | Snowflake Lua 和 `spring.factories` |

## 9. infra-ai

| 子包 | 类型 |
| --- | --- |
| `chat` | 17 个：Service、Client、四 Provider、OpenAI 抽象/SSE、Probe、回调、取消 |
| `embedding` | 7 个：Service/Client、路由、抽象、三 Provider |
| `rerank` | 5 个：Service/Client、路由、百炼、Noop |
| `vlm` | `VlmService`、`RoutingVlmService` |
| `model` | Selector、Target、RoutingExecutor、HealthStore、Validator、Caller |
| `http` | URL、响应、媒体类型和错误 |
| `config` | `AIModelProperties` |
| `enums` | Capability、Provider、Tier |
| `token` | TokenCounter 与启发式实现 |
| `until` | LLM 响应清理、日志预览保护 |

`embedding/LEARNING.md` 与 `rerank/LEARNING.md` 是已有局部学习文档。

## 10. mcp-server

该模块仅 6 个生产 Java 类型：

- `McpServerApplication`
- `McpServerConfig`
- `SalesMcpExecutor`
- `TicketMcpExecutor`
- `WeatherMcpExecutor`
- `YouComSearchMcpExecutor`

另有 You.com 单元测试和受环境变量控制的 Live Test。它不依赖主服务模块，展示 MCP 工具声明与执行。

## 11. frontend

| 目录 | 文件数 | 内容 |
| --- | ---: | --- |
| `pages` | 27 | 登录、聊天、预览、变更日志和 13 类后台页面/辅助组件 |
| `components` | 43 | chat、layout、session、document、common、ui |
| `services` | 15 | 后端领域 API |
| `stores` | 3 | auth、chat、theme |
| `hooks` | 3 | auth/chat 门面与 SSE |
| `utils` | 4 | 时间、存储、查询字符串、错误 |
| `lib` | 3 | UI class 合并、CSV/Markdown 文本转换 |
| `styles` | 1 | 全局 Tailwind/CSS |

入口和框架文件：

- `main.tsx`
- `App.tsx`
- `router.tsx`
- `types/index.ts`
- `vite.config.ts`
- `tailwind.config.cjs`
- `components.json`
- TypeScript/ESLint/Prettier/PostCSS 配置。

`frontend/@/components/ui` 是另一套已跟踪 UI 文件，不在 Vite alias 指向的 `src` 下，详见审查报告。

## 12. resources、docs 与 scripts

### 12.1 数据库

- `schema_pg.sql`
- `init_data_pg.sql`
- `upgrade_v1.0_to_v1.1.sql` 至 `upgrade_v1.5_to_v1.6.sql`
- `backups/` 中的旧 MySQL 风格 schema/data

### 12.2 Docker

- 本地 PostgreSQL/pgvector + RustFS 中间件；
- RocketMQ 标准/AMD Compose；
- Milvus 完整与 lightweight 版本；
- LightRAG + Neo4j GDS Dockerfile、Compose、环境示例和说明。

### 12.3 文档与示例

- `docs/USER_GUIDE.md`
- PDF 摄取 Markdown/JSON 示例；
- 版本说明；
- 两张架构 SVG；
- `resources/docs/knowledge` 下的示例知识库文档。

### 12.4 其他

- `scripts/sse_queue_test.sh`：并发 SSE/排队测试；
- `resources/format/copyright.txt`：Spotless 版权头；
- 根 Maven Wrapper、License、Lombok 与 Git 配置。

## 13. 从功能反查源码

| 想理解的功能 | 第一入口 |
| --- | --- |
| 聊天 | `StreamChatPipeline` |
| SSE 事件 | `StreamChatEventHandler` + `chatStore` |
| 检索 | `RetrievalEngine` |
| 通道融合 | `MultiChannelRetrievalEngine` + PostProcessors |
| 意图 | `DefaultIntentClassifier` + `IntentResolver` |
| MCP | `McpClientAutoConfiguration` + `RetrievalEngine` |
| 文档入库 | `KnowledgeDocumentServiceImpl` |
| 流水线 | `IngestionEngine` |
| 解析/分块 | `DocumentParserSelector` + `ChunkingStrategyFactory` |
| 模型路由 | `ModelSelector` + `RoutingLLMService` |
| 取消 | `StreamTaskManager` |
| 会话记忆 | `DefaultConversationMemoryService` |
| Trace | `StreamChatTraceRunner` + `RagTraceAspect` |
