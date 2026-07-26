# embedding 模块学习文档

> **包路径**：`com.hjs.study.ragent.infra.embedding`
> **定位**：文本向量化的统一接入层 —— 多厂商适配 + 批量分片 + 故障转移
> **文件数**：7 个 Java 文件

---

## 一、模块概述

这个模块是 Ragent 项目中**文本向量化（Embedding）的统一入口**。它的核心使命是：

> 屏蔽阿里云百炼、SiliconFlow、Ollama、AIHubMix 等多个 Embedding 模型厂商的差异，向上层业务提供统一的 `EmbeddingService` 接口；支持单文本向量化和批量向量化，具备自动故障转移能力。

**与 chat 模块的关系**：embedding 模块是 chat 模块的"简化镜像"——同样的分层架构（Service → Routing → Client → Abstract），同样依赖 `infra/model` 模块的 `ModelSelector` 和 `ModelRoutingExecutor` 做路由和故障转移。区别在于 embedding 没有流式场景，也没有档位机制，但多了批量分片处理。

## 二、架构全景图

```
                        ┌─────────────────────────┐
                        │    EmbeddingService      │  ← 业务层统一入口（接口）
                        │ embed() / embedBatch()   │
                        └────────────┬────────────┘
                                     │
                        ┌────────────▼────────────┐
                        │  RoutingEmbeddingService  │  ← 路由实现（@Primary）
                        │  委托 ModelRoutingExecutor│
                        └────────────┬────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              │                      │                      │
    ┌─────────▼────────┐  ┌──────────▼──────┐  ┌────────────▼────────┐
    │   ModelSelector  │  │ModelRoutingExec │  │  ModelHealthStore   │  ← infra/model 模块
    │ selectEmbedding  │  │ executeWith     │  │  (断路器状态)        │
    │ Candidates()     │  │ Fallback()      │  └─────────────────────┘
    └──────────────────┘  └─────────────────┘
```

**EmbeddingClient 实现层**（与 chat 模块完全对称）：

```
                       ┌──────────────────┐
                       │ EmbeddingClient  │  ← 接口
                       └────────┬─────────┘
                                │
                 ┌──────────────┼──────────────┬──────────────┐
                 │              │              │              │
    ┌────────────▼──────┐ ┌────▼────────┐ ┌───▼──────────┐ ┌▼──────────────┐
    │SiliconFlow        │ │AIHubMix     │ │  Ollama      │ │(可扩展其他厂商)│
    │EmbeddingClient    │ │EmbeddingCl..│ │EmbeddingCl.. │ │               │
    └────────┬──────────┘ └───┬─────────┘ └──┬───────────┘ └───────────────┘
             │                │              │
             └────────────────┼──────────────┘
                              │
           ┌──────────────────▼────────────────────┐
           │  AbstractOpenAIStyleEmbeddingClient     │  ← 抽象基类（模板方法）
           │  doEmbed() / embedBatch()              │
           └────────────────────────────────────────┘
```

## 三、分层对比：embedding vs chat

embedding 模块和 chat 模块是"孪生兄弟"，架构高度对称：

| 层次 | chat 模块 | embedding 模块 | 差异 |
|------|----------|---------------|------|
| 业务接口 | `LLMService` | `EmbeddingService` | embedding 多了 `embedBatch()` 批量方法 |
| 路由实现 | `RoutingLLMService` | `RoutingEmbeddingService` | embedding 没有流式，都走同步执行器 |
| 厂商接口 | `ChatClient` | `EmbeddingClient` | embedding 没有 `streamChat()` |
| 抽象基类 | `AbstractOpenAIStyleChatClient` | `AbstractOpenAIStyleEmbeddingClient` | embedding 多了 `maxBatchSize()` 分批逻辑 |
| 选择策略 | `selectChatCandidates()` (档位机制) | `selectEmbeddingCandidates()` (defaultModel+priority) | embedding 无档位概念 |
| 流式基础设施 | `StreamCallback`/`ProbeStreamBridge`/... | **无** | embedding 不需要流式 |

一句话总结：**embedding 就是去掉"流式"和"档位"后的同步版 chat 模块**。

## 四、逐文件深入解析

### 4.1 EmbeddingService（业务层统一入口）

```java
public interface EmbeddingService {
    List<Float> embed(String text);                       // 单文本向量化（自动选模型）
    List<Float> embed(String text, String modelId);       // 单文本向量化（指定模型）
    List<List<Float>> embedBatch(List<String> texts);     // 批量向量化
    List<List<Float>> embedBatch(List<String> texts, String modelId); // 批量+指定模型
    default int dimension() { return 0; }                 // 向量维度
}
```

**四个重载方法的语义**：

| 方法 | 使用场景 | 模型选择 |
|------|---------|---------|
| `embed(text)` | 查询向量化（Query） | 自动选：按 defaultModel+priority 排序后取第一个 |
| `embed(text, modelId)` | 指定模型查询 | 使用指定 modelId，不走候选列表排序 |
| `embedBatch(texts)` | 文档索引构建（Indexing） | 同 embed(text) |
| `embedBatch(texts, modelId)` | 指定模型批量索引 | 同 embed(text, modelId) |

带 `modelId` 参数的重载用于需要"指定模型"的场景：比如跨环境同步向量时，需要确保两个环境用同一个模型。不带 modelId 的重载走 `ModelSelector` 自动选择。

---

### 4.2 EmbeddingClient（厂商级接口）

```java
public interface EmbeddingClient {
    String provider();                                // 厂商标识
    List<Float> embed(String text, ModelTarget target);           // 单文本
    List<List<Float>> embedBatch(List<String> texts, ModelTarget target); // 批量
}
```

与 `ChatClient` 的对比：
- 没有 `streamChat()` 方法（embedding 不需要流式）
- `embed()` 返回 `List<Float>`（向量），而非 `String`
- `embedBatch()` 是多文本的批量版本

---

### 4.3 AbstractOpenAIStyleEmbeddingClient（模板方法基类）

这是模块的核心，封装了 `/v1/embeddings` API 的通用逻辑。

```
AbstractOpenAIStyleEmbeddingClient
│
├─ 公有接口实现（最终方法）
│   ├─ embed(text, target)     → doEmbed([text], target)
│   └─ embedBatch(texts, target) → 检查 maxBatchSize → doEmbed / 分批循环
│
├─ 模板方法
│   └─ doEmbed(texts, target)  → HTTP POST /v1/embeddings → 解析响应
│
└─ 钩子方法（子类按需覆写）
    ├─ provider()          → 厂商标识（抽象）
    ├─ requiresApiKey()    → 默认 true，Ollama 覆写为 false
    ├─ customizeRequestBody() → 默认加 encoding_format=float
    └─ maxBatchSize()      → 默认 0（不限制），子类可设上限
```

#### 4.3.1 embedBatch 的分批逻辑

```java
public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
    if (CollUtil.isEmpty(texts)) return Collections.emptyList();

    int batch = maxBatchSize();
    if (batch <= 0 || texts.size() <= batch) {
        return doEmbed(texts, target);  // 不需要分批，直接调用
    }

    // 需要分批：每批最多 batch 条
    List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
    for (int i = 0, n = texts.size(); i < n; i += batch) {
        int end = Math.min(i + batch, n);
        List<String> slice = texts.subList(i, end);
        List<List<Float>> part = doEmbed(slice, target);
        for (int k = 0; k < part.size(); k++) {
            results.set(i + k, part.get(k));  // 按原始索引放回
        }
    }
    return results;
}
```

**设计要点**：
- 初始化一个与输入等长的 null 列表，分批填充对应位置，保证输出顺序与输入一致
- 分片之间是**串行**的（没有并行化），因为 embedding API 通常有并发限制
- `SiliconFlowEmbeddingClient` 和 `AIHubMixEmbeddingClient` 覆写了 `maxBatchSize()=32`，而 `OllamaEmbeddingClient` 保持默认 0（不限制，本地推理没有 API 限流）

#### 4.3.2 doEmbed 的核心流程

```
1. 校验 provider 配置 + API Key
2. 构建 JSON 请求体：
   {
     "model": "qwen3-embedding",
     "input": ["文本1", "文本2", ...],
     "dimensions": 4096,
     "encoding_format": "float"   ← customizeRequestBody
   }
3. HTTP POST → 校验状态码 → 解析 JSON
4. 处理 API 错误（json 中有 "error" 字段时抛异常）
5. 从 data[].embedding 提取 float 向量列表
6. 返回 List<List<Float>>
```

**与 chat 模块的 AbstractOpenAIStyleChatClient 对比**：

| 特性 | Chat 抽象基类 | Embedding 抽象基类 |
|------|-------------|-------------------|
| 超时管理 | 档位超时预算 + 客户端缓存 | 无（走 HTTP 默认超时） |
| 流式支持 | doStreamChat + SSE 循环 | 无 |
| 请求体 | messages 数组 + 生成参数 | input 数组 + dimensions |
| 响应解析 | choices[0].message.content | data[].embedding float 数组 |
| 错误处理 | HTTP 状态码 | HTTP 状态码 + json.error 字段 |
| 批量处理 | 无 | maxBatchSize 分批循环 |
| 构造函数注入 | @Autowired 字段注入 | 构造器注入 OkHttpClient |

---

### 4.4 三个厂商实现对比

| 厂商 | 类名 | requiresApiKey | maxBatchSize | customizeRequestBody | 特点 |
|------|------|:---:|:---:|------|------|
| SiliconFlow | `SiliconFlowEmbeddingClient` | true | 32 | 继承默认（加 encoding_format） | 开源模型 MaaS |
| AIHubMix | `AIHubMixEmbeddingClient` | true | 32 | 继承默认 | 模型聚合平台 |
| Ollama | `OllamaEmbeddingClient` | **false** | 0（不限制） | **空覆写**（不加 encoding_format） | 本地推理 |

三个实现都只需覆写极少量方法：

```java
// SiliconFlow: 只需 provider + 批量上限
@Service
public class SiliconFlowEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {
    public SiliconFlowEmbeddingClient(OkHttpClient syncHttpClient) { super(syncHttpClient); }
    public String provider() { return ModelProvider.SILICON_FLOW.getId(); }
    protected int maxBatchSize() { return 32; }
}

// Ollama: 无 API Key + 不限制批量 + 不加 encoding_format
@Service
public class OllamaEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {
    public OllamaEmbeddingClient(OkHttpClient syncHttpClient) { super(syncHttpClient); }
    public String provider() { return ModelProvider.OLLAMA.getId(); }
    protected boolean requiresApiKey() { return false; }
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        // Ollama 不需要 encoding_format 字段
    }
}
```

**Ollama 为什么覆写 customizeRequestBody 为空？**

Ollama 的 `/v1/embeddings` 接口不接受 `encoding_format` 字段，传了会报错。所以 Ollama 客户端把这个钩子的默认行为（加 `encoding_format=float`）清空了。

---

### 4.5 RoutingEmbeddingService（路由实现）

```java
@Service
@Primary
public class RoutingEmbeddingService implements EmbeddingService {
    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final Map<String, EmbeddingClient> clientsByProvider;

    // 构造函数：收集所有 EmbeddingClient Bean，按 provider 名建索引
    public RoutingEmbeddingService(ModelSelector selector, ModelRoutingExecutor executor,
                                    List<EmbeddingClient> clients) {
        this.clientsByProvider = clients.stream()
            .collect(Collectors.toMap(EmbeddingClient::provider, Function.identity()));
    }
}
```

**四个 embed/embedBatch 方法的实现模式完全一致**——委托 `ModelRoutingExecutor.executeWithFallback`：

```java
public List<Float> embed(String text) {
    return executor.executeWithFallback(
        ModelCapability.EMBEDDING,                    // 能力类型
        selector.selectEmbeddingCandidates(),          // 候选列表（来自 ModelSelector）
        this::resolveClient,                           // 客户端解析：provider → EmbeddingClient
        (client, target) -> client.embed(text, target) // 实际调用
    );
}
```

**带 modelId 参数的重载**：

```java
public List<Float> embed(String text, String modelId) {
    return executor.executeWithFallback(
        ModelCapability.EMBEDDING,
        List.of(resolveTarget(modelId)),  // ← 只用一个候选，不排序
        this::resolveClient,
        (client, target) -> client.embed(text, target)
    );
}

private ModelTarget resolveTarget(String modelId) {
    // 从候选列表中精确匹配指定 modelId 的 ModelTarget
    return selector.selectEmbeddingCandidates().stream()
        .filter(target -> modelId.equals(target.id()))
        .findFirst()
        .orElseThrow(() -> new RemoteException("Embedding 模型不可用: " + modelId));
}
```

**设计要点**：
- 不带 modelId 的方法：`ModelSelector.selectEmbeddingCandidates()` 返回按 defaultModel+priority 排序的完整候选列表，`ModelRoutingExecutor` 从中做故障转移
- 带 modelId 的方法：只用指定的那一个模型，不走候选列表排序和故障转移。如果指定的模型不可用，直接抛异常
- `resolveClient` 方法比 chat 模块的 `RoutingLLMService` 更简洁——embedding 不需要断路器准入检查（`allowCall`），因为 embedding 的故障转移完全由 `ModelRoutingExecutor` 内部处理

---

## 五、完整调用链路

### 5.1 一次自动选择模型的单文本向量化

```
业务代码: embeddingService.embed("RAG 是什么？")
    │
    ▼
RoutingEmbeddingService.embed("RAG 是什么？")
    │
    ├─ 1. selector.selectEmbeddingCandidates()
    │     → ModelSelector
    │        ├─ 获取 embedding 组的 candidates 配置
    │        ├─ filterAndSortCandidates(candidates, defaultModel)
    │        │    ├─ 过滤 enabled=false
    │        │    ├─ defaultModel 匹配者置顶
    │        │    └─ 按 priority 升序 → id 字典序
    │        └─ buildAvailableTargets → 检查断路器健康 → 剔除不健康节点
    │     → [qwen3-embedding, bge-large-zh, ...]
    │
    ├─ 2. executor.executeWithFallback(EMBEDDING, targets, resolveClient, caller)
    │     │
    │     ├─ 尝试 qwen3-embedding (provider=siliconflow):
    │     │   ├─ resolveClient → SiliconFlowEmbeddingClient
    │     │   ├─ healthStore.allowCall("qwen3-embedding") → true
    │     │   └─ client.embed("RAG 是什么？", target)
    │     │        └─ doEmbed(["RAG 是什么？"], target)
    │     │             ├─ POST /v1/embeddings
    │     │             │   body: {"model":"Qwen/Qwen3-Embedding-8B",
    │     │             │          "input":["RAG 是什么？"],
    │     │             │          "dimensions":4096,
    │     │             │          "encoding_format":"float"}
    │     │             ├─ 200 OK
    │     │             └─ data[0].embedding → [0.023, -0.078, ...]
    │     │   → markSuccess → return
    │     │
    │     └─ (后续候选不会被尝试)
    │
    └─ 3. 返回 List<Float>
```

### 5.2 一次指定模型的批量向量化

```
业务代码: embeddingService.embedBatch(texts, "bge-large-zh")
    │
    ▼
RoutingEmbeddingService.embedBatch(texts, "bge-large-zh")
    │
    ├─ 1. resolveTarget("bge-large-zh")
    │     → 从 selectEmbeddingCandidates 中精确匹配 id="bge-large-zh"
    │     → 找到: ModelTarget(id="bge-large-zh", provider=siliconflow, ...)
    │     → candidates = [这个唯一的 target]
    │
    ├─ 2. executor.executeWithFallback(EMBEDDING, [bge-large-zh], resolveClient, caller)
    │     │
    │     └─ client.embedBatch(texts, target)
    │          └─ AbstractOpenAIStyleEmbeddingClient.embedBatch()
    │               ├─ texts.size() = 100, maxBatchSize() = 32
    │               ├─ 需要分批: [0..31] → POST → 32 vectors
    │               ├─          [32..63] → POST → 32 vectors
    │               ├─          [64..95] → POST → 32 vectors
    │               └─          [96..99] → POST → 4 vectors
    │               → 合并结果：100 个向量按原始顺序返回
    │
    └─ 3. 返回 List<List<Float>>
```

### 5.3 故障转移示例

```
业务代码: embeddingService.embed("测试文本")
    │
    ├─ targets = [siliconflow-qwen3, aihubmix-bge, ollama-nomic]
    │
    ├─ 尝试 siliconflow-qwen3:
    │   └─ HTTP 500 → markFailure → 尝试下一个
    │
    ├─ 尝试 aihubmix-bge:
    │   └─ HTTP 200 → markSuccess → return [...]
    │
    └─ （ollama-nomic 不会被调用）
```

---

## 六、组件职责速查表

| 文件 | 类型 | 一句话职责 |
|------|------|-----------|
| `EmbeddingService` | 接口 | 业务层统一入口：embed() + embedBatch() + dimension() |
| `RoutingEmbeddingService` | @Service @Primary | 路由实现：委托 ModelSelector + ModelRoutingExecutor 做故障转移 |
| `EmbeddingClient` | 接口 | 厂商级接口：provider() + embed() + embedBatch() |
| `AbstractOpenAIStyleEmbeddingClient` | 抽象类 | OpenAI 协议模板：doEmbed() + 分批逻辑 + 3 个钩子 |
| `SiliconFlowEmbeddingClient` | @Service | 硅基流动实现，maxBatchSize=32 |
| `AIHubMixEmbeddingClient` | @Service | AIHubMix 实现，maxBatchSize=32 |
| `OllamaEmbeddingClient` | @Service | Ollama 本地推理，无 API Key，不限制批量，不加 encoding_format |

## 七、关键学习要点

### 7.1 为什么 embedding 没有流式？

Embedding 的响应是固定长度的 float 数组（如 4096 维向量），数据量小、生成时间短、结果不可分片消费。流式的价值在于"边生成边消费"降低用户感知延迟，而 embedding 不存在这个需求——等一个完整向量返回才是最自然的交互方式。

### 7.2 为什么 embedding 没有档位（Tier）机制？

Chat 场景的不同档位对应不同的模型能力和响应速度（FAST→快、DEEP→强推理）。而 embedding 模型的能力主要体现在向量维度（dimension）上——维度越高表达能力越强但计算量越大。这个取舍直接在配置中通过 `defaultModel` 和 `priority` 表达，不需要额外的 Tier 抽象层。

### 7.3 maxBatchSize 为什么要串行分片？

两个原因：
1. **API 限流**：大多数 Embedding API 有并发限制，并行发 4 个请求可能全部被限流
2. **实现简单**：串行分片天然保证顺序正确，不需要额外的合并逻辑

如果未来需要优化吞吐，可以在子类中覆写 `embedBatch` 加入线程池并发分片——模板方法模式让这种扩展变得容易。

### 7.4 为什么带 modelId 的重载不用候选列表而只用一个 target？

带 modelId 的方法语义是"我就要用这个模型，不要换"。如果这个模型不可用，直接报错比静默切换到另一个模型更符合调用方的预期——调用方指定 modelId 通常有明确意图（如跨环境一致性、特定维度要求）。

### 7.5 与 chat 模块共享哪些 infra/model 组件？

| 共享组件 | 在 embedding 中的用法 | 在 chat 中的用法 |
|---------|----------------------|-------------------|
| `ModelSelector` | `selectEmbeddingCandidates()` — defaultModel+priority 排序 | `selectChatCandidates()` — 档位机制 |
| `ModelRoutingExecutor` | `executeWithFallback()` — 故障转移 | 同步：同样用；流式：不走执行器 |
| `ModelHealthStore` | 被 `ModelSelector.buildModelTarget` 间接使用（预过滤） | 被 `ModelSelector` + `ModelRoutingExecutor` 双重使用 |
| `ModelTarget` | 数据载体（record），同 chat | 同 embedding |
| `ModelCaller<C,T>` | `executeWithFallback` 的 caller 参数 | 同步同样使用 |

---

*文档生成时间：基于代码结构分析自动生成。建议结合 `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/model/LEARNING.md` 和 `infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/chat/LEARNING.md` 一起阅读，形成完整的"模型路由 → Chat → Embedding"知识体系。*
