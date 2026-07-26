# Ragent Monster 详细使用手册

本文档面向本地开发、功能体验、联调和日常维护，内容与仓库当前配置保持一致。默认方案使用 PostgreSQL + pgvector，不要求启动 Milvus、Elasticsearch、Neo4j 或 LightRAG。

## 目录

1. [系统组成](#1-系统组成)
2. [环境准备](#2-环境准备)
3. [部署本地中间件](#3-部署本地中间件)
4. [配置模型与应用](#4-配置模型与应用)
5. [构建并启动完整项目](#5-构建并启动完整项目)
6. [首次登录与基础设置](#6-首次登录与基础设置)
7. [知识库使用流程](#7-知识库使用流程)
8. [问答与会话](#8-问答与会话)
9. [管理后台功能](#9-管理后台功能)
10. [摄取流水线](#10-摄取流水线)
11. [API 调用示例](#11-api-调用示例)
12. [可选能力配置](#12-可选能力配置)
13. [日常运维与数据备份](#13-日常运维与数据备份)
14. [测试与发布构建](#14-测试与发布构建)
15. [常见问题排查](#15-常见问题排查)
16. [生产部署建议](#16-生产部署建议)

## 1. 系统组成

### 1.1 服务与职责

| 服务                | 进程/容器                | 职责                                     |
| ------------------- | ------------------------ | ---------------------------------------- |
| Web 前端            | Vite `5173`              | 登录、问答、知识库和管理后台             |
| Ragent 主服务       | `bootstrap` JAR，`9090`  | REST API、SSE、RAG、用户和管理功能       |
| MCP Server          | `mcp-server` JAR，`9099` | 提供销售、工单、天气示例工具             |
| PostgreSQL/pgvector | `ragent-postgres`        | 22 张业务表、向量字段和 HNSW 索引        |
| Redis               | `redis`                  | Sa-Token、会话状态、限流、分布式锁与幂等 |
| RocketMQ            | NameServer + Broker      | 文档分块、知识库清理、消息反馈异步任务   |
| RustFS              | `ragent-rustfs`          | S3 兼容文档和多模态资产存储              |

### 1.2 核心数据流

文档入库链路：

```text
上传文件或配置 URL
  → RustFS 保存源文件
  → 文档解析
  → 文本清洗与分块
  → Embedding
  → t_knowledge_vector / pgvector
  → 文档与分块状态更新
```

问答链路：

```text
用户问题
  → 会话记忆与问题改写
  → 意图树识别知识库范围
  → 向量召回
  → RRF 融合与 Rerank
  → LLM 生成回答
  → SSE 流式返回
  → Trace、消息和反馈落库
```

## 2. 环境准备

### 2.1 必需软件

| 软件           | 最低建议 | 检查命令                 |
| -------------- | -------- | ------------------------ |
| JDK            | 17       | `java -version`          |
| Docker         | 24       | `docker version`         |
| Docker Compose | v2       | `docker compose version` |
| Node.js        | 18       | `node --version`         |
| npm            | 9        | `npm --version`          |

仓库带有 Maven Wrapper，不要求全局安装 Maven。

### 2.2 资源建议

- 仅运行中间件、后端和前端：建议至少 8 GB 内存。
- 同时运行 Ollama 本地模型：建议 16 GB 以上内存，并根据模型准备 GPU/显存。
- PostgreSQL 的 Compose 已配置 `256mb` 共享内存。
- 默认上传单文件上限为 50 MB，单次请求上限为 100 MB。

### 2.3 进入项目目录

Windows PowerShell：

```powershell
Set-Location "D:\IntelliJ IDEA 2025.3.3\projectDeraction\ragent_monster"
```

macOS / Linux：

```bash
cd /path/to/ragent_monster
```

## 3. 部署本地中间件

### 3.1 默认连接参数

| 中间件         | 主机地址                | 账号/隔离设置               |
| -------------- | ----------------------- | --------------------------- |
| PostgreSQL     | `127.0.0.1:5432/ragent` | `postgres / postgres`       |
| Redis          | `127.0.0.1:16379`       | 无密码，database `15`       |
| RocketMQ       | `127.0.0.1:9876`        | NameServer                  |
| RustFS API     | `http://127.0.0.1:9000` | `rustfsadmin / rustfsadmin` |
| RustFS Console | `http://127.0.0.1:9001` | 同上                        |

这些值只适用于本地开发。

### 3.2 启动 PostgreSQL/pgvector 与 RustFS

项目 Compose 会创建两个独立数据卷：

- `ragent-postgres-data`
- `ragent-rustfs-data`

启动：

```powershell
docker compose -f resources/docker/ragent-local-middleware.compose.yaml up -d
```

查看状态：

```powershell
docker compose -f resources/docker/ragent-local-middleware.compose.yaml ps
```

首次创建空数据库时，PostgreSQL 会按顺序执行：

1. `resources/database/schema_pg.sql`
2. `resources/database/init_data_pg.sql`

其中会启用 `vector` 扩展、创建 22 张表、创建 `vector(1536)` 字段与 HNSW 索引，并写入初始化管理员。

> 初始化 SQL 只会在 PostgreSQL 数据目录为空时执行。修改 SQL 后简单重启容器不会重新初始化数据库。

### 3.3 Redis：复用现有容器

当前本机使用名为 `redis` 的 Redis 6.2 容器：

```powershell
docker start redis
docker exec redis redis-cli -n 15 PING
```

预期返回：

```text
PONG
```

项目只使用 database `15`，不要为了初始化项目而清理 Redis 的其他 database。

### 3.4 Redis：容器不存在时新建

如果本机没有可复用 Redis，可以创建项目专用容器：

```powershell
docker run -d `
  --name redis `
  --restart unless-stopped `
  -p 127.0.0.1:16379:6379 `
  -v ragent-redis-data:/data `
  redis:6.2 `
  redis-server --appendonly yes
```

macOS / Linux 请将 PowerShell 续行符改为反斜杠：

```bash
docker run -d \
  --name redis \
  --restart unless-stopped \
  -p 127.0.0.1:16379:6379 \
  -v ragent-redis-data:/data \
  redis:6.2 \
  redis-server --appendonly yes
```

### 3.5 RocketMQ：复用当前本机容器

当前已验证的容器为：

- `rocketmq-learning-namesrv`
- `rocketmq-learning-broker`

启动顺序：

```powershell
docker start rocketmq-learning-namesrv
docker start rocketmq-learning-broker
```

项目依赖三个 Topic。Broker 禁用自动创建 Topic 时必须显式创建：

```powershell
docker exec rocketmq-learning-broker sh /home/rocketmq/rocketmq-5.5.0/bin/mqadmin updateTopic -n rocketmq-learning-namesrv:9876 -c DefaultCluster -t knowledge-document-chunk_topic
docker exec rocketmq-learning-broker sh /home/rocketmq/rocketmq-5.5.0/bin/mqadmin updateTopic -n rocketmq-learning-namesrv:9876 -c DefaultCluster -t knowledge-base-cleanup_topic
docker exec rocketmq-learning-broker sh /home/rocketmq/rocketmq-5.5.0/bin/mqadmin updateTopic -n rocketmq-learning-namesrv:9876 -c DefaultCluster -t message-feedback_topic
```

检查 Topic：

```powershell
docker exec rocketmq-learning-broker sh /home/rocketmq/rocketmq-5.5.0/bin/mqadmin topicList -n rocketmq-learning-namesrv:9876
```

### 3.6 RocketMQ：使用仓库内 Compose

若不存在上述容器，可启动仓库提供的 RocketMQ 5.2.0 NameServer 和 Broker。为了降低本地内存占用，默认不启动 Dashboard：

```powershell
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d rmqnamesrv rmqbroker
```

此时创建 Topic 的命令为：

```powershell
docker exec rmqbroker sh /home/rocketmq/rocketmq-5.2.0/bin/mqadmin updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t knowledge-document-chunk_topic
docker exec rmqbroker sh /home/rocketmq/rocketmq-5.2.0/bin/mqadmin updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t knowledge-base-cleanup_topic
docker exec rmqbroker sh /home/rocketmq/rocketmq-5.2.0/bin/mqadmin updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t message-feedback_topic
```

不要同时启动两套占用相同宿主机端口的 RocketMQ。

### 3.7 验证中间件

```powershell
# PostgreSQL 与 RustFS
docker compose -f resources/docker/ragent-local-middleware.compose.yaml ps

# PostgreSQL 连接、扩展与表数量
docker exec ragent-postgres psql -U postgres -d ragent -c "SELECT extversion FROM pg_extension WHERE extname='vector';"
docker exec ragent-postgres psql -U postgres -d ragent -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"
docker exec ragent-postgres psql -U postgres -d ragent -c "SET hnsw.iterative_scan = relaxed_order;"

# Redis DB 15
docker exec redis redis-cli -n 15 PING

# RustFS 健康检查
Invoke-WebRequest http://127.0.0.1:9000/health
```

预期结果：

- pgvector 版本为 `0.8.2`
- public schema 中有 `22` 张表
- HNSW iterative scan 设置成功
- Redis 返回 `PONG`
- PostgreSQL 与 RustFS 显示 `healthy`

## 4. 配置模型与应用

主配置文件：

```text
bootstrap/src/main/resources/application.yaml
```

### 4.1 最小可用模型配置

完整的知识库问答至少需要：

1. 一个可用的聊天模型
2. 一个输出 1536 维向量的 Embedding 模型
3. Rerank 模型可选；默认候选中包含 `noop` 降级

推荐的云端组合：

```powershell
$env:BAILIAN_API_KEY="你的百炼 API Key"
$env:SILICONFLOW_API_KEY="你的 SiliconFlow API Key"
```

用途：

| 环境变量                            | 默认用途                                  |
| ----------------------------------- | ----------------------------------------- |
| `BAILIAN_API_KEY`                   | Qwen 聊天、Qwen3 Rerank、Qwen-VL          |
| `SILICONFLOW_API_KEY`               | 默认 Qwen3-Embedding-8B、GLM 深度思考候选 |
| `AIHUBMIX_API_KEY`                  | GPT 聊天与备用 Embedding                  |
| `MINERU_API_KEY`                    | PDF、Word、PPT 等复杂文档解析             |
| `YDC_API_KEY`                       | 可选 Web Search                           |
| `OSS_ACCESS_KEY` / `OSS_SECRET_KEY` | 切换阿里云 OSS 时使用                     |

环境变量只对从当前终端启动的 Java 进程生效。IDE 启动时需要在 Run Configuration 中配置相同变量。

### 4.2 使用 Ollama

默认 Ollama 地址：

```text
http://localhost:11434
```

默认本地候选模型：

```powershell
ollama pull qwen3:8b-fp16
ollama pull qwen3-embedding:8b-fp16
```

使用本地 Embedding 时必须保证输出维度与以下配置一致：

```yaml
rag:
  default:
    dimension: 1536
```

数据库字段也是 `vector(1536)`。修改维度后需要同步调整数据库结构并重新生成已有向量，不能只改 YAML。

### 4.3 关键应用配置

| 配置                          | 当前值              | 说明                   |
| ----------------------------- | ------------------- | ---------------------- |
| `server.port`                 | `9090`              | 后端端口               |
| `server.servlet.context-path` | `/api/ragent`       | API 上下文             |
| `spring.datasource.url`       | PostgreSQL `ragent` | 业务与向量数据库       |
| `spring.data.redis.database`  | `15`                | Redis 数据隔离         |
| `rocketmq.name-server`        | `127.0.0.1:9876`    | RocketMQ NameServer    |
| `rag.storage.type`            | `s3`                | 使用 RustFS/S3         |
| `rag.vector.type`             | `pg`                | 使用 pgvector          |
| `rag.keyword.type`            | `none`              | 关闭 ES 关键词后端     |
| `rag.graph.type`              | `none`              | 关闭 LightRAG 图谱后端 |
| `rag.default.dimension`       | `1536`              | 向量维度               |
| `rag.default.sse-timeout-ms`  | `300000`            | SSE 5 分钟超时         |
| `rag.trace.enabled`           | `true`              | 开启全链路 Trace       |

### 4.4 使用环境变量覆盖连接

Spring Boot 支持使用环境变量覆盖 YAML，例如：

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/ragent"
$env:SPRING_DATASOURCE_USERNAME="postgres"
$env:SPRING_DATASOURCE_PASSWORD="postgres"
$env:SPRING_DATA_REDIS_HOST="127.0.0.1"
$env:SPRING_DATA_REDIS_PORT="16379"
$env:SPRING_DATA_REDIS_DATABASE="15"
$env:ROCKETMQ_NAME_SERVER="127.0.0.1:9876"
$env:RAG_STORAGE_S3_ENDPOINT="http://127.0.0.1:9000"
```

生产环境应使用环境变量或独立 Profile，不应直接修改并提交敏感值。

## 5. 构建并启动完整项目

### 5.1 后端构建

Windows：

```powershell
.\mvnw.cmd -DskipTests package
```

macOS / Linux：

```bash
./mvnw -DskipTests package
```

成功后生成：

```text
bootstrap/target/bootstrap-1.0-SNAPSHOT.jar
mcp-server/target/mcp-server-1.0-SNAPSHOT.jar
```

### 5.2 启动 MCP Server

先启动 MCP，否则主服务启动时无法加载示例工具：

```powershell
java -jar mcp-server/target/mcp-server-1.0-SNAPSHOT.jar
```

预期日志包含：

```text
Tomcat started on port 9099
Started McpServerApplication
```

### 5.3 启动主服务

在另一个终端中保留模型环境变量并启动：

```powershell
java -jar bootstrap/target/bootstrap-1.0-SNAPSHOT.jar
```

预期日志应能看到：

- Redis 连接到 `127.0.0.1:16379`
- Hikari 连接池启动完成
- 三个 RocketMQ Consumer 启动
- RustFS 桶 `ragent-sources`、`ragent-assets` 初始化
- MCP Server 返回 3 个工具
- Tomcat 启动在 `9090`，上下文为 `/api/ragent`

### 5.4 使用 IDE 启动

也可以分别运行：

- `com.hjs.study.ragent.mcp.McpServerApplication`
- `com.hjs.study.ragent.RagentApplication`

注意：

1. 工作目录建议设为仓库根目录。
2. 两个应用都应使用 JDK 17 或更高版本。
3. 模型 API Key 需要加入 IDE 的环境变量。
4. 仍应先启动 MCP，再启动主服务。

### 5.5 启动前端

首次安装依赖：

```powershell
Set-Location frontend
npm install
```

开发模式：

```powershell
npm run dev
```

前端 `.env` 默认设置：

```dotenv
VITE_API_BASE_URL=/api/ragent
```

Vite 会将 `/api` 代理到 `http://localhost:9090`。

访问：

```text
http://127.0.0.1:5173
```

### 5.6 启动顺序

推荐顺序：

1. PostgreSQL、RustFS
2. Redis
3. RocketMQ NameServer
4. RocketMQ Broker
5. MCP Server
6. Ragent 主服务
7. Web 前端

前端可以晚于后端启动；主服务应在中间件健康后启动。

## 6. 首次登录与基础设置

初始化账号：

```text
用户名：admin
密码：admin
角色：admin
```

登录后建议立即：

1. 打开右上角用户菜单修改密码。
2. 进入“用户管理”创建个人管理员账号。
3. 确认模型环境变量已配置。
4. 打开“系统设置”核对当前 RAG 参数。
5. 创建测试知识库并上传小型 Markdown/TXT 文档验证链路。

管理员和普通用户的可见菜单不同。知识库、用户、意图树、Trace 等管理功能需要管理员权限。

## 7. 知识库使用流程

### 7.1 创建知识库

进入：

```text
管理后台 → 知识库
```

创建时主要填写：

| 字段            | 说明                                         |
| --------------- | -------------------------------------------- |
| 名称            | 面向用户的知识库名称                         |
| Embedding 模型  | 应与应用可用的 Embedding 候选一致            |
| Collection Name | 向量空间标识，建议使用小写字母、数字和下划线 |

虽然字段仍沿用 `collectionName` 命名，但默认 pgvector 模式下会作为逻辑隔离字段使用，并不要求启动 Milvus。

### 7.2 上传文档

进入知识库详情页后上传文件。后端当前可识别：

- PDF
- Markdown / MD
- TXT
- CSV
- XLS / XLSX
- PNG / JPG / JPEG
- SVG
- URL 来源

上传限制：

- 单文件最大 50 MB
- 单请求最大 100 MB

复杂 PDF、Word、PPT 解析通常需要 `MINERU_API_KEY`。简单文本、Markdown、CSV、Excel 等可直接使用本地解析器。

### 7.3 处理模式

文档支持两种处理模式：

| 模式       | `processMode` | 适用场景                         |
| ---------- | ------------- | -------------------------------- |
| 直接分块   | `chunk`       | 普通文档快速入库                 |
| 摄取流水线 | `pipeline`    | 需要抓取、增强、清洗或自定义节点 |

直接分块策略：

| 策略     | `chunkStrategy`   | 特点                       |
| -------- | ----------------- | -------------------------- |
| 固定长度 | `fixed_size`      | 参数简单，适合纯文本       |
| 结构感知 | `structure_aware` | 保留标题、列表、表格等结构 |

固定长度示例配置：

```json
{
  "chunkSize": 512,
  "overlapSize": 128
}
```

结构感知示例：

```json
{
  "targetChars": 1400,
  "maxChars": 1800,
  "minChars": 600,
  "overlapChars": 0
}
```

### 7.4 开始分块

上传完成后执行“分块/向量化”。系统会通过 RocketMQ 异步执行：

1. 下载或读取源文档
2. 解析正文与资产
3. 按策略生成 Chunk
4. 调用 Embedding
5. 写入 `t_knowledge_vector`
6. 更新文档状态和分块日志

若任务失败，优先查看：

- 文档详情中的分块日志
- 后端日志
- `knowledge-document-chunk_topic` 是否存在
- Embedding API Key 是否有效
- RustFS 文件是否可读

### 7.5 查看和编辑 Chunk

文档详情页可：

- 查看 Chunk 列表
- 新增或修改 Chunk
- 删除 Chunk
- 单条或批量启用/禁用
- 查看分块日志
- 预览源文档

手工修改 Chunk 后，应确认向量是否被同步更新；涉及大批量内容变更时建议重新执行文档分块。

### 7.6 URL 与定时刷新

URL 来源可配置：

- `sourceType=url`
- `sourceLocation`
- `scheduleEnabled=true`
- `scheduleCron`

调度器默认每 10 秒扫描到期任务，最小调度间隔为 60 秒。不要设置过密的 Cron，以免持续抓取外部站点并重复生成向量。

## 8. 问答与会话

### 8.1 发起问答

进入：

```text
问答页面 → 新建会话
```

输入问题后，前端使用 SSE 接收：

- 任务与排队状态
- 流式回答片段
- 引用来源
- 会话与消息 ID
- 完成或异常事件

### 8.2 深度思考

开启“深度思考”后使用 `deep` 模型档位。默认候选为：

- 百炼 `qwen3-max`
- SiliconFlow `GLM-4.7`

深度思考超时配置为 120 秒，比普通问答更耗时和费用。

### 8.3 会话记忆

默认保留最近 8 轮历史；从第 9 轮开始可生成会话摘要。系统还会：

- 自动生成会话标题
- 保存历史消息
- 支持重命名和删除会话
- 将用户上下文和 Trace 信息传递到异步线程

### 8.4 引用、反馈与推荐问题

回答完成后可以：

- 打开引用来源面板
- 对回答点赞或点踩
- 取消已有反馈
- 生成推荐追问

反馈会通过 `message-feedback_topic` 异步处理。

### 8.5 停止任务

前端停止按钮会调用：

```text
POST /api/ragent/rag/v3/stop?taskId=...
```

停止操作只影响指定问答任务，不会删除已保存的会话。

## 9. 管理后台功能

| 页面       | 路径                      | 用途                    |
| ---------- | ------------------------- | ----------------------- |
| 仪表盘     | `/admin/dashboard`        | 概览、趋势和性能指标    |
| 知识库     | `/admin/knowledge`        | 知识库、文档和 Chunk    |
| 知识图谱   | `/admin/knowledge-graph`  | LightRAG 开启后查看图谱 |
| 意图树     | `/admin/intent-tree`      | 树形意图范围配置        |
| 意图列表   | `/admin/intent-list`      | 意图节点列表与编辑      |
| 摄取流水线 | `/admin/ingestion`        | Pipeline 与任务执行情况 |
| RAG Trace  | `/admin/traces`           | 运行列表和节点详情      |
| 审计日志   | `/admin/change-logs`      | 关键管理操作记录        |
| 系统设置   | `/admin/settings`         | 当前 RAG 配置查看       |
| 示例问题   | `/admin/sample-questions` | 首页/问答页示例问题     |
| 词条映射   | `/admin/mappings`         | 查询词归一化和映射      |
| 用户管理   | `/admin/users`            | 创建、修改和删除用户    |

### 9.1 意图树

意图树用于把问题路由到特定知识库。建议：

1. 顶层节点使用业务域，例如“人事”“财务”“IT”。
2. 子节点描述具体问题范围。
3. 将叶子节点绑定目标知识库。
4. 先小范围启用，通过 Trace 观察命中分数。
5. 置信度低时系统会回退全局向量检索。

### 9.2 Query Term Mapping

词条映射适合处理：

- 缩写与全称
- 产品别名
- 部门内部术语
- 常见错别字

映射应保持精确，过度扩展会污染召回结果。

### 9.3 RAG Trace

Trace 页面用于诊断：

- 哪个意图被命中
- 每个检索通道返回多少候选
- RRF 与 Rerank 后的顺序
- 模型调用耗时
- 节点输入输出
- 异常发生在哪个阶段

生产环境需要结合数据安全要求限制 Trace 中记录的输入输出内容。

## 10. 摄取流水线

摄取流水线支持将文档处理拆为多个节点。当前核心节点包括：

| 节点     | 作用                              |
| -------- | --------------------------------- |
| Fetcher  | 获取上传文件、HTTP URL 或其他来源 |
| Parser   | 提取文本、表格、图片等结构        |
| Enhancer | 使用模型增强或清洗内容            |
| Enricher | 补充标题、摘要、元数据等          |
| Chunker  | 生成文本块                        |
| Indexer  | 生成向量并写入索引                |

节点通过 `nextNodeId` 连接。引擎会查找未被其他节点引用的起点，并沿链执行，同时检测循环依赖。

完整请求示例见：

- [PDF 摄取流水线示例](examples/pdf-ingestion-example.md)
- [Pipeline JSON 示例](examples/pdf-pipeline-request.json)

使用 Pipeline 前应确认：

1. 节点 ID 唯一。
2. `nextNodeId` 指向存在的节点。
3. 不存在循环。
4. Indexer 位于处理链末端。
5. 需要 AI 的节点已有相应模型配置。

## 11. API 调用示例

API 基础地址：

```text
http://127.0.0.1:9090/api/ragent
```

统一响应通常为：

```json
{
  "code": "0",
  "message": null,
  "data": {},
  "requestId": null,
  "success": true
}
```

### 11.1 登录并保存 Token

PowerShell：

```powershell
$loginBody = @{
  username = "admin"
  password = "admin"
} | ConvertTo-Json

$login = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:9090/api/ragent/auth/login" `
  -ContentType "application/json; charset=utf-8" `
  -Body $loginBody

$token = $login.data.token
$headers = @{ Authorization = $token }
```

后续请求直接发送 Token，不需要添加 `Bearer` 前缀。

### 11.2 查询当前用户

```powershell
Invoke-RestMethod `
  -Uri "http://127.0.0.1:9090/api/ragent/user/me" `
  -Headers $headers
```

### 11.3 创建知识库

```powershell
$kbBody = @{
  name = "IT 支持知识库"
  embeddingModel = "qwen-emb-8b"
  collectionName = "it_support"
} | ConvertTo-Json

$kb = Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:9090/api/ragent/knowledge-base" `
  -Headers $headers `
  -ContentType "application/json; charset=utf-8" `
  -Body $kbBody

$kbId = $kb.data.id
```

### 11.4 上传文档

使用 `curl.exe` 发送 multipart：

```powershell
curl.exe -X POST `
  "http://127.0.0.1:9090/api/ragent/knowledge-base/$kbId/docs/upload" `
  -H "Authorization: $token" `
  -F "file=@resources/docs/knowledge/group/it/IT支持.md" `
  -F "sourceType=file" `
  -F "processMode=chunk" `
  -F "chunkStrategy=structure_aware" `
  -F 'chunkConfig={"targetChars":1400,"maxChars":1800,"minChars":600,"overlapChars":0}'
```

从响应的 `data.id` 取得文档 ID。

### 11.5 启动文档分块

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:9090/api/ragent/knowledge-base/docs/$docId/chunk" `
  -Headers $headers
```

查询文档详情：

```powershell
Invoke-RestMethod `
  -Uri "http://127.0.0.1:9090/api/ragent/knowledge-base/docs/$docId" `
  -Headers $headers
```

### 11.6 SSE 流式问答

```powershell
curl.exe -N -G `
  "http://127.0.0.1:9090/api/ragent/rag/v3/chat" `
  -H "Authorization: $token" `
  --data-urlencode "question=公司的 VPN 无法连接时应该如何排查？" `
  --data-urlencode "deepThinking=false"
```

继续已有会话时增加：

```text
--data-urlencode "conversationId=会话ID"
```

### 11.7 会话接口

```powershell
# 会话列表
Invoke-RestMethod `
  -Uri "http://127.0.0.1:9090/api/ragent/conversations" `
  -Headers $headers

# 会话消息
Invoke-RestMethod `
  -Uri "http://127.0.0.1:9090/api/ragent/conversations/$conversationId/messages" `
  -Headers $headers
```

## 12. 可选能力配置

### 12.1 Milvus

启动仓库中的 Milvus Compose 后修改：

```yaml
rag:
  vector:
    type: milvus
milvus:
  uri: http://localhost:19530
```

可选 Compose：

- `resources/docker/lightweight/milvus-stack-2.6.6.compose.yaml`
- `resources/docker/milvus-stack-2.6.6.compose.yaml`

切换向量后端不会自动迁移 pgvector 中已有向量，需要重新索引文档。

### 12.2 Elasticsearch 关键词检索

需要先准备 Elasticsearch 和分析器，再修改：

```yaml
rag:
  keyword:
    type: es
  search:
    channels:
      keyword:
        enabled: true
```

只打开 `keyword.enabled` 而没有把后端类型改为 `es` 不会得到有效关键词召回。

### 12.3 LightRAG / 知识图谱

仓库提供：

```text
resources/docker/graphrag/lightrag-neo4j-stack.compose.yaml
```

读取图谱用于检索：

```yaml
rag:
  graph:
    type: lightrag
  search:
    channels:
      graph:
        enabled: true
```

若还需要在向量写入后同步重建图谱：

```yaml
rag:
  graph:
    ingestion:
      enabled: true
```

图谱后端、图谱检索和图谱写入是三个不同开关，应根据成本分别开启。

### 12.4 Web Search

```powershell
$env:YDC_API_KEY="你的搜索 API Key"
```

```yaml
rag:
  search:
    channels:
      web-search:
        enabled: true
```

外部搜索结果可能引入不可信内容，生产环境应增加来源白名单、超时和内容安全策略。

### 12.5 阿里云 OSS

```powershell
$env:OSS_ACCESS_KEY="..."
$env:OSS_SECRET_KEY="..."
```

```yaml
rag:
  storage:
    type: oss
```

还需要按实际区域修改 OSS endpoint、region、bucket 和 public URL。生产环境不要继续使用默认 RustFS 凭据。

### 12.6 自定义 MCP Server

默认配置：

```yaml
rag:
  mcp:
    servers:
      - name: default
        url: http://localhost:9099
```

新增 Server 时可继续向列表添加配置。自定义本仓库 MCP 工具时，实现 `McpToolExecutor` 并注册为 Spring Bean，主服务会从 MCP Server 拉取工具清单。

## 13. 日常运维与数据备份

### 13.1 查看服务状态

```powershell
docker ps
docker compose -f resources/docker/ragent-local-middleware.compose.yaml ps
```

检查端口：

```powershell
Get-NetTCPConnection -State Listen -LocalPort 5173,9090,9099,5432,9000,9001,9876,16379
```

### 13.2 查看日志

```powershell
docker logs -f ragent-postgres
docker logs -f ragent-rustfs
docker logs -f redis
docker logs -f rocketmq-learning-namesrv
docker logs -f rocketmq-learning-broker
```

Java 服务以前台方式运行时直接查看对应终端。生产环境应交给 systemd、容器平台或进程管理器，并配置日志轮转。

### 13.3 停止服务

先停止前端、主服务和 MCP，再停止中间件：

```powershell
docker compose -f resources/docker/ragent-local-middleware.compose.yaml stop
docker stop rocketmq-learning-broker
docker stop rocketmq-learning-namesrv
docker stop redis
```

`stop` 不会删除容器和数据卷。

### 13.4 PostgreSQL 备份

生成自定义格式备份并复制到当前目录：

```powershell
docker exec ragent-postgres pg_dump -U postgres -d ragent -Fc -f /tmp/ragent.dump
docker cp ragent-postgres:/tmp/ragent.dump ./ragent.dump
```

恢复前应先确认目标数据库和备份版本：

```powershell
docker cp ./ragent.dump ragent-postgres:/tmp/ragent.dump
docker exec ragent-postgres pg_restore -U postgres -d ragent --clean --if-exists /tmp/ragent.dump
```

恢复属于破坏性操作，应先备份当前数据库，并在维护窗口执行。

### 13.5 RustFS 数据

RustFS 使用 Docker 卷 `ragent-rustfs-data`。建议通过 S3 兼容客户端进行桶级同步或备份：

- 私有源文件桶：`ragent-sources`
- 公开资产桶：`ragent-assets`

不要直接复制正在写入中的卷目录作为唯一备份方案。

### 13.6 数据库升级

数据库升级脚本位于：

```text
resources/database/upgrade_v*.sql
```

升级原则：

1. 先备份数据库。
2. 确认当前 schema 版本。
3. 按版本顺序执行升级脚本。
4. 检查表、索引和 pgvector 扩展。
5. 再启动新版本应用。

## 14. 测试与发布构建

### 14.1 后端

```powershell
# 执行全部测试
.\mvnw.cmd test

# 打包但跳过测试执行，仍会编译测试源码
.\mvnw.cmd -DskipTests package

# 仅格式化
.\mvnw.cmd spotless:apply
```

注意：Spotless 在 Maven 编译阶段自动执行，可能修改 Java 文件。

### 14.2 前端

```powershell
Set-Location frontend

npm run lint
npm run build
npm run preview
```

生产构建输出位于 `frontend/dist`，不应手工修改。

### 14.3 冒烟测试清单

- 所有必需容器处于运行/健康状态
- `vector` 扩展版本为 0.8.2
- 后端与 MCP 启动无连接错误
- 管理员能够登录
- 能创建知识库
- 能上传并完成一个小型 Markdown 文档分块
- 能发起 SSE 问答
- 回答中能看到引用来源
- Trace 页面能找到对应运行
- 前端生产构建成功

## 15. 常见问题排查

### 15.1 PostgreSQL 容器健康但没有表

原因通常是数据卷已经存在，初始化 SQL 不会再次执行。

检查：

```powershell
docker exec ragent-postgres psql -U postgres -d ragent -c "\dt"
docker logs ragent-postgres
```

如果卷中已有业务数据，应执行升级脚本或手工迁移，不能直接删除卷。只有确认是全新的、无业务数据的本地环境时，才可以重建 PostgreSQL 数据卷。

### 15.2 `SET hnsw.iterative_scan` 失败

说明 pgvector 版本过旧。确认镜像：

```powershell
docker inspect ragent-postgres --format "{{.Config.Image}}"
```

当前要求：

```text
pgvector/pgvector:0.8.2-pg15-bookworm
```

### 15.3 Redis 连接失败

检查：

```powershell
docker ps --filter name=redis
docker exec redis redis-cli -n 15 PING
Test-NetConnection 127.0.0.1 -Port 16379
```

默认配置没有 Redis 密码。如果复用的 Redis 启用了密码，需要在 Spring 配置中补充密码。

### 15.4 RocketMQ Consumer 启动失败

常见原因：

- NameServer 未启动
- Broker 尚未就绪
- 三个业务 Topic 未创建
- 宿主机 `9876` 端口不通
- Broker 向客户端广播了不可访问的 IP

先检查：

```powershell
docker logs rocketmq-learning-namesrv
docker logs rocketmq-learning-broker
docker exec rocketmq-learning-broker sh /home/rocketmq/rocketmq-5.5.0/bin/mqadmin clusterList -n rocketmq-learning-namesrv:9876
docker exec rocketmq-learning-broker sh /home/rocketmq/rocketmq-5.5.0/bin/mqadmin topicList -n rocketmq-learning-namesrv:9876
```

### 15.5 RustFS 连接或上传失败

检查：

```powershell
Invoke-WebRequest http://127.0.0.1:9000/health
docker logs ragent-rustfs
```

主服务启动后应自动创建：

- `ragent-sources`
- `ragent-assets`

若 endpoint 与浏览器访问地址不同，需要配置 `rag.storage.s3.public-url`。

### 15.6 文档一直停留在处理中

依次检查：

1. `knowledge-document-chunk_topic` 是否存在。
2. 对应 Consumer 是否启动。
3. Embedding 服务是否可用。
4. 文档源文件是否存在于 RustFS。
5. PDF/Office 文档是否缺少 MinerU Key。
6. 文档分块日志中是否记录了异常。

### 15.7 Embedding 报维度不匹配

默认数据库字段为 `vector(1536)`。Embedding 模型输出维度必须为 1536。切换模型或修改 `rag.default.dimension` 后，必须同步迁移数据库并重新向量化全部文档。

### 15.8 主服务启动后 MCP 工具为 0

确认：

```powershell
Test-NetConnection 127.0.0.1 -Port 9099
```

主服务启动时会读取 MCP 工具清单。若 MCP 晚于主服务启动，最简单的本地处理方式是先确认 MCP 正常，再重启主服务。

### 15.9 登录成功但接口返回未登录

检查请求头：

```text
Authorization: 登录接口返回的 token
```

不要默认添加 `Bearer`。同时检查 Redis DB 15 是否可用，以及前端本地存储中是否存在过期 Token。

### 15.10 前端请求 404 或连接失败

确认：

- 后端端口是 `9090`
- API 上下文是 `/api/ragent`
- `frontend/.env` 中 `VITE_API_BASE_URL=/api/ragent`
- `frontend/vite.config.ts` 代理目标是 `http://localhost:9090`
- 修改 `.env` 或 Vite 配置后已重启前端开发服务

### 15.11 中文日志在 PowerShell 中显示乱码

项目源码和 Maven 编码为 UTF-8。旧版 Windows PowerShell 可能用系统代码页读取 UTF-8 日志。可以先执行：

```powershell
chcp 65001
$OutputEncoding = [System.Text.Encoding]::UTF8
```

也可使用 Windows Terminal + PowerShell 7。不要仅因为终端显示异常就批量改写源码编码。

### 15.12 端口被占用

```powershell
Get-NetTCPConnection -State Listen -LocalPort 5173,9090,9099,5432,9000,9001,9876,16379 |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

确认占用进程属于哪个项目后再决定停止进程或修改端口。

## 16. 生产部署建议

本地配置不能直接用于生产。至少完成以下调整：

### 16.1 凭据与权限

- 修改默认管理员密码。
- 使用高强度 PostgreSQL、Redis、RustFS 密码。
- 模型和对象存储密钥通过 Secret 管理系统注入。
- 为数据库和对象存储创建最小权限账号。
- 定期轮换凭据和 Sa-Token 配置。

### 16.2 网络

- 不对公网暴露 PostgreSQL、Redis、RocketMQ、RustFS 管理端口。
- 使用防火墙、安全组或容器网络限制访问。
- 前端与 API 通过 HTTPS 反向代理。
- 正确配置 RocketMQ Broker 广播地址。

### 16.3 数据

- PostgreSQL 与对象存储使用受控持久化存储。
- 建立定期全量与增量备份。
- 定期验证备份可恢复。
- 对上传文档、Trace 和模型输出实施数据分类与脱敏。

### 16.4 可用性

- Java 服务交给 systemd、Kubernetes 或其他进程管理器。
- 配置健康检查、优雅停止和日志轮转。
- 对 PostgreSQL、Redis、RocketMQ 和对象存储建立监控。
- 根据并发量调整 Hikari、Redisson、线程池和 RAG 限流参数。

### 16.5 发布前检查

- 完整测试通过
- 前端生产构建通过
- 数据库升级脚本已演练
- API Key 和密码未出现在 Git 差异中
- 默认管理员密码已修改
- 可选检索通道与实际部署的中间件一致
- 备份、告警和回滚方案已验证
