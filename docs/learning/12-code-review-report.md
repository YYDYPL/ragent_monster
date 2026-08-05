# 代码审查报告

> 审查基线：`445730538941698b265303442737cfe910e684d4`
>
> 审查范围：仓库内全部第一方已跟踪文件；不包含第三方依赖、构建产物、IDE 元数据和未跟踪文件。
>
> 本报告只记录当前实现及风险，不修改业务代码，也不提供二次开发步骤。

## 1. 如何阅读本报告

严重度用于表达“若系统按生产方式暴露，该问题的优先级”，不是对项目整体质量的评价：

| 级别 | 含义 |
| --- | --- |
| P0 / 严重 | 可直接导致核心安全边界失效，应在生产使用前处理 |
| P1 / 高 | 可导致越权、敏感数据暴露或显著业务影响 |
| P2 / 中 | 正确性、可靠性、可维护性或工程保障存在明确缺口 |
| P3 / 低 | 仓库卫生、文档一致性或长期演进问题 |

每项结论均包含证据、影响、建议和验证方法。“已确认问题”表示源码足以证明现象存在；“设计权衡”表示当前方案有明确代价，但不能脱离部署规模和业务要求直接判定为缺陷。

## 2. 结论摘要

| 编号 | 严重度 | 分类 | 结论 |
| --- | --- | --- | --- |
| CR-01 | P0 | 已确认问题 | 密码以明文保存并用字符串相等判断 |
| CR-02 | P1 | 已确认问题 | 后端绝大多数管理接口只校验登录，没有校验管理员角色 |
| CR-03 | P1 | 已确认问题 | 流式任务取消只按 `taskId` 执行，没有校验任务所有者 |
| CR-04 | P1 | 已确认问题 | 远程抓取器可请求任意 URL，缺少 SSRF 边界控制 |
| CR-05 | P1 | 已确认问题 | 对话问题进入 URL、日志和 Trace，存在敏感信息扩散面 |
| CR-06 | P2 | 已确认问题 | 多子问题结果按意图 ID 合并时可能覆盖先前证据 |
| CR-07 | P2 | 已确认问题 | GET 聊天接口会产生持久化副作用，且前端会自动重试 |
| CR-08 | P2 | 已确认问题 | Maven `compile` 生命周期绑定 `spotless:apply`，构建会改源码 |
| CR-09 | P2 | 已确认问题 | 前端认证核心 Store 关闭类型检查，且前端没有自动化测试 |
| CR-10 | P2 | 已确认问题 | 数据库演进、索引和枚举注释存在漂移与重复 |
| CR-11 | P3 | 已确认问题 | 仓库跟踪了 TypeScript 构建缓存和重复 UI 目录 |
| CR-12 | P2 | 已确认问题 | 默认 Maven 测试无法启动 Fork JVM，构建插件版本也未固定 |
| CR-13 | P2 | 已确认问题 | 上下文格式测试依赖 LF 换行，在 Windows CRLF 工作树失败 |
| CR-14 | P2 | 已确认问题 | 前端 Lint 配置与锁定的插件配置格式不兼容 |

优先顺序应先建立服务端安全边界：密码哈希、后端 RBAC、任务所有权和 URL 出站策略；再处理隐私、检索正确性与工程保障问题。

## 3. 已确认问题

### CR-01：密码明文保存与比较

**严重度：P0 / 严重**

**证据**

- [`AuthServiceImpl.passwordMatches`](../../bootstrap/src/main/java/com/hjs/study/ragent/user/service/impl/AuthServiceImpl.java) 直接执行已存密码与输入密码的字符串比较。
- [`UserServiceImpl`](../../bootstrap/src/main/java/com/hjs/study/ragent/user/service/impl/UserServiceImpl.java) 在创建和更新用户时直接写入传入的密码，并使用相同的比较方式。
- 初始化 SQL 会写入默认账户凭据；本报告不复制具体值，参见 [`init_data_pg.sql`](../../resources/database/init_data_pg.sql)。

**影响**

数据库快照、备份、SQL 日志或只读查询权限一旦泄漏，攻击者即可获得可直接使用的密码；相同密码在其他系统复用时还会扩大影响。单纯隐藏前端输入或加密传输不能弥补服务端静态存储风险。

**建议**

- 使用成熟的自适应密码哈希算法，例如 Argon2id 或 BCrypt；每个密码使用独立盐值。
- 登录时只调用密码编码器的 `matches`，禁止还原密码。
- 迁移现有账户：可选择强制重置，或在首次成功验证后升级哈希；迁移策略必须审计且不可记录原始密码。
- 默认账户应在首次启动时生成一次性凭据或要求显式初始化，不能把可用密码固化在生产镜像中。

**验证方法**

创建两个密码相同的账户，确认数据库中的哈希不同；确认正确密码可登录、错误密码失败；检查应用日志、Trace、异常和 SQL 输出均不包含原始密码。

### CR-02：后端管理接口缺少角色授权

**严重度：P1 / 高**

**证据**

- [`SaTokenConfig`](../../bootstrap/src/main/java/com/hjs/study/ragent/user/config/SaTokenConfig.java) 对受保护路径统一执行 `StpUtil.checkLogin()`，只建立“已登录”边界。
- 服务端显式角色检查集中在 [`UserController`](../../bootstrap/src/main/java/com/hjs/study/ragent/user/controller/UserController.java) 的用户管理操作。
- 知识库、知识入库、意图树、术语映射、仪表盘、Trace、审计、RAG 设置和示例问题等控制器没有对应的管理员角色检查，详见[接口目录](09-api-config-and-prompts.md)。
- 前端 [`RequireAdmin`](../../frontend/src/router.tsx) 只控制页面导航，浏览器端守卫不是服务端安全边界。

**影响**

普通登录用户可绕过前端菜单，直接调用管理接口读取诊断信息或修改系统数据。若与远程抓取、MCP 配置或模型配置能力组合，影响会进一步扩大。

**建议**

- 建立统一的服务端授权模型，并在控制器或应用服务边界声明所需角色/权限。
- 采用“默认拒绝”策略：新管理接口若没有显式权限声明，应无法访问。
- 对知识库进一步考虑资源级授权，而不只区分管理员和普通用户。
- 增加普通用户、管理员、未登录用户三组集成测试，覆盖所有控制器。

**验证方法**

以普通用户直接请求每个管理端点，期望统一返回 403；管理员请求成功；未登录请求返回 401。不能只通过前端页面是否可见来验证。

### CR-03：取消任务没有所有权校验

**严重度：P1 / 高**

**证据**

- [`RAGChatController.stop`](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/controller/RAGChatController.java) 接收 `taskId` 后直接调用服务。
- [`RAGChatServiceImpl.stopTask`](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/service/impl/RAGChatServiceImpl.java) 将该 ID 交给任务管理器。
- [`StreamTaskManager.cancel`](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/service/handler/StreamTaskManager.java) 按任务 ID 取消本地或分布式任务，任务注册与取消路径没有携带或核对当前用户 ID。

**影响**

只要获得或猜中另一个用户的任务 ID，已登录用户就可能终止对方的流式回答。任务 ID 的随机性只能降低发现概率，不能替代授权。

**建议**

- 任务注册时同时保存 `taskId`、`userId`、会话 ID 和必要的租户边界。
- 取消时执行原子“按任务 ID 与所有者匹配”操作；管理员代操作应是独立权限。
- 分布式取消消息也应携带经校验的身份上下文或服务端生成的不可伪造任务句柄。

**验证方法**

创建用户 A、B 的并发流式任务；A 取消自己的任务应成功，A 使用 B 的任务 ID 应返回 404 或 403，B 的流不能被影响。

### CR-04：远程 URL 抓取缺少 SSRF 防护

**严重度：P1 / 高**

**证据**

- [`HttpClientHelper`](../../bootstrap/src/main/java/com/hjs/study/ragent/ingestion/util/HttpClientHelper.java) 可从调用方给出的 URL 直接构造 HTTP 请求。
- [`RemoteFileFetcher`](../../bootstrap/src/main/java/com/hjs/study/ragent/knowledge/handler/RemoteFileFetcher.java) 实现了大小限制和临时文件清理，但没有限制协议、主机、解析后的 IP、端口或跳转目标。
- [`HttpUrlFetcher`](../../bootstrap/src/main/java/com/hjs/study/ragent/ingestion/strategy/fetcher/HttpUrlFetcher.java) 允许附加认证请求头，扩大了凭据被发送到非预期目标的风险。
- 相关管理接口又受到 CR-02 的影响。

**影响**

攻击者可能诱导服务访问环回地址、云元数据地址、内网管理端口或其他不可从公网访问的资源；DNS 重绑定和重定向会绕过只检查原始字符串的简单方案。

**建议**

- 仅允许明确协议，默认拒绝 `file:` 等非 HTTP(S) 协议。
- 解析 DNS 后拒绝环回、链路本地、私网、保留地址和本机网络；每次重定向都重新校验。
- 配置允许域名/端口清单，并在网络层设置出站代理或防火墙。
- 限制响应体、连接时间、跳转次数和内容类型；认证头只能发送到明确授权的源。

**验证方法**

覆盖 `localhost`、IPv4/IPv6 私网、十进制/混合编码 IP、DNS 重绑定、跨域重定向、超大响应和慢响应测试；所有被禁止目标在发出实际连接前失败。

### CR-05：问题、参数与 Trace 的敏感信息扩散

**严重度：P1 / 高**

**证据**

- 聊天接口 [`RAGChatController`](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/controller/RAGChatController.java) 使用 GET 查询参数接收问题，URL 容易进入浏览器历史、网关和访问日志。
- [`StreamChatTraceRunner`](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/trace/StreamChatTraceRunner.java) 将原始问题写入 Trace 扩展数据。
- [`DefaultIntentClassifier`](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/core/intent/DefaultIntentClassifier.java) 会记录问题及意图判定信息。
- [`McpClientToolExecutor`](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/core/mcp/McpClientToolExecutor.java) 会记录工具参数；参数可能包含用户数据或访问条件。
- Trace 查询接口还受到 CR-02 的影响。

**影响**

用户问题、检索条件和工具参数可能包含个人信息、内部项目名、业务机密或凭据。它们会在 URL、应用日志、Trace 数据库和日志采集平台形成多份副本，扩大访问者与保留周期。

**建议**

- 将聊天改为 POST 请求并通过请求体传递问题；流仍可用 Fetch 读取响应体。
- 为日志和 Trace 建立字段级分类、脱敏、采样、访问控制和保留期限。
- 默认只记录长度、哈希、模型、耗时、状态和安全的结构化元数据；原文记录应显式启用。
- MCP 参数按工具 Schema 做允许字段日志与敏感字段掩码，不直接打印完整 Map。

**验证方法**

用带邮箱、手机号、令牌样式字符串的测试问题贯穿聊天、检索和 MCP；检查网关日志、应用日志、Trace 表和前端错误输出，确认原文未出现或按策略脱敏。

### CR-06：多子问题的意图证据可能被覆盖

**严重度：P2 / 中**

**证据**

[`RetrievalEngine.retrieve`](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/core/retrieval/RetrievalEngine.java) 遍历子问题结果时，通过 `mergedIntentChunks.putAll(context.intentChunks())` 合并“意图 → 分块”映射。当两个子问题命中相同意图 ID 时，后加入的值会覆盖先前值，而不是合并分块。

**影响**

格式化的逐子问题文本仍可能包含两组结果，但用于来源展示、Grounding 或后续 Prompt 组织的意图映射可能只保留后一组证据。表现取决于下游读取哪个字段，因此属于已确认的数据结构覆盖问题，而不是断言所有回答都会丢失内容。

**建议**

- 对相同意图 ID 执行追加合并，再按文档/分块 ID 去重并保持稳定顺序。
- 明确定义跨子问题总预算和单意图预算，避免简单追加导致 Prompt 膨胀。
- 增加“两个子问题命中同一意图、不同分块”的回归测试，同时验证 Prompt、来源和 Trace。

**验证方法**

构造两个子问题命中同一意图的固定检索桩，断言最终映射同时包含两组唯一分块，且顺序与预算符合契约。

### CR-07：GET 聊天具有持久化副作用并可被自动重试

**严重度：P2 / 中**

**证据**

- [`RAGChatController`](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/controller/RAGChatController.java) 将流式聊天暴露为 GET，但执行过程会创建消息、更新会话并触发异步处理。
- 前端 [`createStreamResponse`](../../frontend/src/hooks/useStreamResponse.ts) 对流建立失败执行一次自动重试。

**影响**

GET 通常被代理、预取器和客户端视为安全且可重试；在首个请求已经到达服务端、但前端尚未收到响应时重试，可能产生重复消息或重复模型成本。CR-05 所述 URL 泄漏也由此加剧。

**建议**

- 改用 POST 流式响应。
- 为每次发送生成客户端请求 ID，并在服务端建立幂等记录；重试复用同一 ID。
- 将“尚未到达服务端”和“服务端已经开始处理但连接中断”区分处理。

**验证方法**

在服务端创建消息后、首个 SSE 事件前主动断开连接，触发前端重试；断言只存在一条用户消息和一次模型任务。

### CR-08：编译生命周期会自动改写源码

**严重度：P2 / 中**

**证据**

根 [`pom.xml`](../../pom.xml) 将 `spotless:apply` 绑定到 Maven `compile` 阶段，而不是只执行只读的格式检查。

**影响**

普通编译、测试或 IDE 导入可能修改工作树；修改会混入开发者正在进行的变更，也会让 CI 构建结果依赖是否允许写入源码目录。代码生成与格式化结果若在不同 JDK/插件版本间变化，还会制造无关差异。

**建议**

- 在默认生命周期绑定 `spotless:check`。
- 将 `spotless:apply` 保留为显式开发命令或单独 Maven Profile。
- CI 只检查格式并在不一致时失败，不自动提交或隐藏改动。

**验证方法**

在干净工作树执行 `mvn compile`，结束后 `git diff --exit-code` 应保持成功；提交一处故意格式错误后，构建应失败但文件内容不变。

### CR-09：前端类型安全与测试保障不足

**严重度：P2 / 中**

**证据**

- [`authStore.ts`](../../frontend/src/stores/authStore.ts) 使用 `// @ts-nocheck` 并关闭 ESLint 检查，认证状态和持久化逻辑不受 TypeScript 约束。
- [`package.json`](../../frontend/package.json) 没有测试脚本，`frontend/src` 下没有第一方测试文件。
- [`chatStore.ts`](../../frontend/src/stores/chatStore.ts) 同时承担流生命周期、消息更新、会话同步、取消和错误恢复，状态转换集中且复杂。

**影响**

认证状态结构漂移、SSE 事件乱序、取消竞态和会话切换等问题主要依赖人工测试发现；核心 Store 关闭检查会使重构风险进一步升高。

**建议**

- 消除 `@ts-nocheck`，为持久化数据建立显式版本和运行时校验。
- 为 SSE 解析器与聊天状态机编写确定性单元测试，覆盖首包失败、结束、错误、取消、重连和会话切换。
- 将传输状态机与 UI/持久化状态拆分，减少单个 Store 的职责。
- 为路由权限、Service 参数和关键页面增加集成测试。

**验证方法**

CI 同时执行 `tsc --noEmit`、Lint 和测试；对每种 SSE 事件序列做表驱动测试，确认最终状态、消息内容和资源释放一致。

### CR-10：数据库演进与 Schema 元数据漂移

**严重度：P2 / 中**

**证据**

- [`schema_pg.sql`](../../resources/database/schema_pg.sql) 对 `t_message` 定义了键列相同的 `idx_conversation_user_time` 与 `idx_conversation_summary`，形成重复索引维护成本。
- 同一 Schema 对 `t_intent_node.kind` 的注释只描述 KB 与 SYSTEM；代码 [`IntentKind`](../../bootstrap/src/main/java/com/hjs/study/ragent/rag/enums/IntentKind.java) 还包含 MCP。
- [`upgrade_v1.0_to_v1.1.sql`](../../resources/database/upgrade_v1.0_to_v1.1.sql) 至 `upgrade_v1.5_to_v1.6.sql` 采用手工顺序升级脚本，项目未集成 Flyway 或 Liquibase 来记录已执行版本和校验和。

**影响**

重复索引增加写放大和存储占用；注释漂移会误导运维与二次开发；手工升级在多环境、重复部署和失败恢复场景下容易发生版本不一致。

**建议**

- 核对查询计划后删除无用途的重复索引。
- 让枚举契约、列约束与注释保持一致，并用测试校验 Java 枚举和数据库允许值。
- 引入可追踪的数据库迁移机制，迁移文件只追加、不原地修改，并在启动或部署阶段校验版本。

**验证方法**

在生产等规模数据上比较索引删除前后的查询计划与写入成本；从空库和每个历史版本分别执行升级，确认最终 Schema 一致且重复执行安全。

### CR-11：构建缓存和重复 UI 源码被跟踪

**严重度：P3 / 低**

**证据**

- 仓库跟踪了 [`tsconfig.app.tsbuildinfo`](../../frontend/tsconfig.app.tsbuildinfo) 与 [`tsconfig.node.tsbuildinfo`](../../frontend/tsconfig.node.tsbuildinfo)，但根 [`.gitignore`](../../.gitignore) 没有忽略 `*.tsbuildinfo`。
- [`frontend/@/components/ui`](../../frontend/@/components/ui) 与 [`frontend/src/components/ui`](../../frontend/src/components/ui) 存在大量同名 UI 组件，形成两个可能漂移的来源。

**影响**

机器相关的增量编译缓存会产生无意义差异；重复组件目录使维护者难以判断真实导入源，修复可能只落在其中一份。

**建议**

- 确认构建不依赖缓存文件后停止跟踪，并在 `.gitignore` 中忽略。
- 依据 `tsconfig` 路径别名与实际 import 统一 UI 源目录；如果另一份是生成物，应明确生成命令并忽略输出。

**验证方法**

清理工作副本后重新安装和构建，确认缓存自动生成且不影响产物；用静态搜索确认所有 UI import 都解析到唯一目录。

### CR-12：默认 Maven 测试无法启动，插件版本未固定

**严重度：P2 / 中**

**证据**

- 根 [`pom.xml`](../../pom.xml) 将 Surefire 的 `argLine` 配置为 `@{argLine} -javaagent:...`，但项目属性中没有为 `argLine` 提供初始值。
- 在当前 Windows/JDK 21 环境执行目标单测时，Fork JVM 收到字面量 `@{argLine}`，以 `Error: could not open '{argLine}'` 退出，测试数为 0。
- 同一 POM 没有固定 `maven-compiler-plugin` 和 `maven-surefire-plugin` 版本；Maven 构建时需要从元数据选择兼容版本并发出有效模型警告。

**影响**

默认 `mvn test` 不能进入测试用例，CI 可能只因 `-DskipTests` 打包成功而误以为质量门槛有效。未固定插件版本又会使不同时间或 Maven 版本选择不同实现，削弱可复现性。

**建议**

- 为迟绑定属性提供空的项目级初始值，再由 JaCoCo 等插件按需写入；同时保留 Mockito Agent。
- 在 `pluginManagement` 中固定 Compiler、Surefire 和其他核心构建插件版本。
- CI 必须断言实际执行测试数大于 0，并保存 Surefire XML。
- 不要把 `forkCount=0` 作为长期修复；它只是绕过 Fork 参数的诊断手段。

**验证方法**

在全新 Maven 缓存、JDK 17 与项目支持的其他 JDK 上执行 `mvn test`；确认 Fork 命令不含占位符、测试确实运行，且有效 POM 中插件版本固定。

### CR-13：上下文格式测试依赖平台换行

**严重度：P2 / 中**

**证据**

- [`DefaultContextFormatterTest`](../../bootstrap/src/test/java/com/hjs/study/ragent/rag/core/prompt/DefaultContextFormatterTest.java) 使用包含 `\n` 的精确子串断言。
- [`context-format.st`](../../bootstrap/src/main/resources/prompt/context-format.st) 由资源加载器原样读取；仓库索引是 LF，但当前 Windows 工作树在没有 EOL 属性约束时检出为 CRLF。
- 用 `forkCount=0` 绕过 CR-12 后，6 个目标类共执行 25 项测试，24 项通过；失败项正是“无标题文档块”对子串换行的断言。

**影响**

同一提交在 Linux 和 Windows 上可能得到不同测试结果；失败会掩盖真正的上下文格式回归。运行时 Prompt 也会随检出平台带入不同换行。

**建议**

- 明确 Prompt 资源的换行契约，可在 [`.gitattributes`](../../.gitattributes) 中固定为 LF。
- 测试若只关心结构，应先统一 `\r\n`/`\r` 为 `\n`；若换行本身属于协议，则对产物显式规范化后再断言。
- 增加 Windows 与 Linux CI 作业验证资源处理一致性。

**验证方法**

分别在 LF 和 CRLF 工作树执行测试；格式化输出和断言结果应一致，且生成的 Prompt 不因操作系统发生语义变化。

### CR-14：前端 Lint 配置无法加载

**严重度：P2 / 中**

**证据**

- [`.eslintrc.cjs`](../../frontend/.eslintrc.cjs) 使用旧式配置并扩展 `plugin:react-refresh/recommended`。
- [`package-lock.json`](../../frontend/package-lock.json) 锁定的 `eslint-plugin-react-refresh` 版本，其 `recommended` 导出为 Flat Config，包含旧式 ESLint 配置 Schema 不接受的 `name` 和对象式 `plugins`。
- `npm run lint` 在检查任何源码前即失败，错误为 `Unexpected top-level property "name"`。

**影响**

Lint 在本地和 CI 中不能充当质量门槛，未使用变量、Hook 依赖和 Fast Refresh 导出规则等问题均无法被自动发现。

**建议**

- 要么迁移到 ESLint Flat Config，要么选择插件提供的兼容旧式配置/固定兼容版本；不要让 `^` 范围与旧配置格式无约束组合。
- 使用 `npm ci` 验证锁文件，并在 CI 中单独执行 Lint，使配置加载失败清晰可见。
- 修复配置后再评估真实规则错误，不能把“命令可启动”视为源码已通过。

**验证方法**

从空 `node_modules` 执行 `npm ci && npm run lint`，应完成全部 TS/TSX 扫描且零警告；同时验证锁文件升级机器人不会再次引入配置格式不兼容。

## 4. 设计权衡与演进约束

以下项目不是在缺少运行背景时可以直接定性的缺陷，但二次开发必须理解其代价。

### 4.1 数据库没有声明外键

22 张表通过 ID 形成逻辑关系，但 [`schema_pg.sql`](../../resources/database/schema_pg.sql) 没有数据库外键。优点是删除、迁移和高吞吐写入更自由；代价是孤儿记录只能依赖应用服务、清理任务和测试避免。若继续保留此设计，应为关键关系建立一致性巡检。

### 4.2 一次业务操作跨越多个资源系统

知识入库与删除会跨 PostgreSQL、对象存储、向量库、Elasticsearch、LightRAG、Redis 和 RocketMQ。数据库事务不能覆盖这些资源。当前状态字段、处理日志、重建和消息机制提供了一定恢复能力，但它本质上是最终一致性系统。需要明确每一步的幂等键、重试上限、补偿方式和对账指标，参见[数据与中间件](07-data-and-middleware.md)。

### 4.3 pgvector 维度固定

Schema 使用固定维度的向量列。更换 Embedding 模型时，输出维度不仅是配置变化，还可能要求迁移列、重算全部向量和重建索引。模型档位切换必须与知识库索引版本绑定。

### 4.4 VLM 路由能力弱于其他模型类型

[`RoutingVlmService`](../../infra-ai/src/main/java/com/hjs/study/ragent/infra/vlm/RoutingVlmService.java) 采用首个候选客户端；Chat、Embedding 和 Rerank 则具有更完整的选择、健康状态和故障转移。若图像解析是核心链路，应明确是否需要同等级别的可用性；若只是可选增强，简单方案可能是合理的。

### 4.5 主服务与 MCP Server 各自集成联网搜索

主应用和独立 `mcp-server` 都有 You.com 接入。代码注释说明这是进程隔离后的有意重复，而不是可直接删除的冗余。维护时应通过契约测试或共享协议文档避免参数语义漂移，而不是强行引入运行时耦合。

### 4.6 可变上下文简化流水线，但增加隐式耦合

聊天与入库流水线通过 Context 对象在阶段间传递和累积状态。它让节点扩展容易，但阶段的前置条件、可写字段和失败后状态依赖约定。新增节点时应声明输入、输出、幂等性、线程安全和中止语义。

### 4.7 认证上下文依赖线程传播

请求入口把用户身份写入上下文，并需要在异步执行器、Reactor 流和 MQ 消费中正确传播或重建。当前项目已有上下文包装和清理意识，但所有新增异步边界都必须重新审视：不能假设 `ThreadLocal` 会自然跨线程存在。

## 5. 改进建议清单

以下建议没有在本次文档任务中实施。

### 第一阶段：生产安全门槛

1. 将所有密码迁移到自适应哈希。
2. 建立后端默认拒绝的 RBAC/资源授权矩阵。
3. 为聊天任务绑定所有者并修复取消授权。
4. 为远程抓取建立 URL、DNS、重定向和网络层出站策略。
5. 收敛问题、Trace 和 MCP 参数中的敏感数据。

### 第二阶段：正确性与一致性

1. 修复同意图多子问题证据覆盖并增加回归测试。
2. 将聊天改为 POST，并用请求 ID 提供端到端幂等。
3. 为跨 PostgreSQL、索引和对象存储的操作建立可观测补偿流程。
4. 引入数据库版本管理，清理重复索引和 Schema 注释漂移。

### 第三阶段：工程保障

1. 默认构建改为 `spotless:check`，格式写入改为显式命令。
2. 恢复认证 Store 的 TypeScript 检查。
3. 建立前端 SSE/Store 测试和后端权限矩阵测试。
4. 为 `framework`、`infra-ai` 的路由、熔断、首包探测和上下文传播补充直接测试。
5. 修复 Surefire `argLine`、固定插件版本，并消除测试的换行依赖。
6. 修复 ESLint 配置后再启用前端零警告门槛。
7. 清理构建缓存与重复 UI 目录。

### 第四阶段：可维护性

1. 拆分职责较多的 [`KnowledgeDocumentServiceImpl`](../../bootstrap/src/main/java/com/hjs/study/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java)，把对象存储、解析调度、索引协调和状态转换形成清晰端口。
2. 为聊天、检索和入库 Context 制定字段所有权与阶段契约。
3. 将模型维度、索引版本和知识库重建策略变成可查询的运行元数据。

## 6. 已有设计亮点

严格审查也应保留对有效设计的判断：

- `infra-ai` 通过统一接口、档位、选择器、健康状态与首包探测，把 Provider 差异限制在基础设施层，参见 [AI 基础设施](06-ai-infrastructure.md)。
- 流式结束采用单次终止控制，取消、完成和异常路径围绕同一任务生命周期收敛，降低重复终止事件概率。
- 检索融合使用 RRF，而不是直接比较不同通道不可比的原始分数；后处理器又通过顺序接口显式编排。
- 入库链路将 Fetcher、Parser、Enhancer、Chunker、Enricher、Indexer 分离，并为结构化文档保留层级信息。
- `SearchChannel`、`IngestionNode`、AI Client 与 `McpToolExecutor` 等接口形成了清晰扩展缝。
- RAG Trace、阶段耗时、模型调用记录和独立 Trace 查询为调用链排障提供了良好基础。
- 模型档位配置具有启动期校验，联网集成测试通过环境变量显式开启，避免默认测试误调用外部服务。
- RAG 设置接口对密钥进行掩码展示，没有把完整 Provider 凭据直接返回前端。

## 7. 建议新增的验证矩阵

| 领域 | 最小验证集 |
| --- | --- |
| 认证 | 密码哈希、旧账户迁移、登出、令牌过期、并发登录策略 |
| 授权 | 80 个端点的匿名/普通用户/管理员矩阵，知识库资源归属 |
| 聊天 | 首包前断线、首包后断线、重复请求 ID、取消竞态、跨用户取消 |
| 检索 | 同意图多子问题、跨通道重复项、空召回、预算边界、Rerank 降级 |
| 入库 | 每节点重试、重复消息、对象已写数据库失败、索引部分成功、删除补偿 |
| 模型路由 | 同步故障转移、流式首包失败、首包后失败、熔断恢复、全部候选失败 |
| URL 抓取 | 私网/环回/IPv6、DNS 重绑定、重定向、认证头、大小与超时 |
| 隐私 | URL、日志、Trace、数据库、MCP 参数、前端错误中的敏感数据扫描 |
| 前端 SSE | 所有事件类型、分片边界、UTF-8、多行数据、取消、错误、会话切换 |
| 数据迁移 | 空库安装、各历史版本升级、重复执行、回滚/前滚、索引计划 |

## 8. 审查边界

- 本报告是静态源码审查，不等同于渗透测试、依赖漏洞扫描或生产配置审计。
- 未实际观察生产网络策略、网关脱敏、数据库权限、密钥注入和日志保留策略；若外部平台已经提供控制，可降低部分暴露概率，但不能替代源码中的授权与所有权校验。
- 外部服务不可用、测试环境缺失与源码缺陷在[测试与可观测性](10-testing-observability.md)中分别记录。
- 为避免泄漏，本报告不复制任何默认密码、连接串、访问密钥或 Provider Key。
