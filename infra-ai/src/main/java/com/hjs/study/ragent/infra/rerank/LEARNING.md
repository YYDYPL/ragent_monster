# rerank 模块学习文档

> **包路径**：`com.hjs.study.ragent.infra.rerank`
> **定位**：RAG 精排层 —— 检索后重排序，提升 LLM 上下文质量
> **文件数**：5 个 Java 文件

---

## 一、模块概述

这个模块是 Ragent 项目中 **RAG 管线的"质检员"**。它的核心使命是：

> 向量检索召回了一批候选文档（通常 topK=20~50），但这些文档的相关性排序并不精确。Rerank 模块使用专门的 Cross-Encoder 模型对每个 (query, document) 对逐一打分，按相关性重新排序，最终只保留最相关的 topN 条（通常 3~8 条）喂给 LLM。

**为什么需要 Rerank？**

向量检索用的是 Bi-Encoder（query 和 document 独立编码后算余弦相似度），速度快但精度有限。Rerank 用的是 Cross-Encoder（query 和 document 拼接后一起编码），精度高但速度慢。两者搭配是 RAG 的经典"召回→精排"两阶段范式：

```
原始文档库（海量）
    │
    ▼  向量检索（Bi-Encoder，快但粗）
候选文档（20~50 条）
    │
    ▼  Rerank（Cross-Encoder，慢但准）
精排文档（3~8 条）→ 喂给 LLM
```

## 二、架构全景图

```
                      ┌─────────────────────┐
                      │   RerankService      │  ← 业务层接口
                      │ rerank(query, docs,  │
                      │        topN)         │
                      └──────────┬──────────┘
                                 │
                      ┌──────────▼──────────┐
                      │ RoutingRerankService │  ← 路由实现（@Primary）
                      │ 委托 ModelRouting    │
                      │ Executor 做故障转移   │
                      └──────────┬──────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                  │
    ┌─────────▼────────┐  ┌──────▼──────┐  ┌────────▼────────┐
    │ BaiLianRerank    │  │  NoopRerank │  │  ModelSelector   │  ← infra/model
    │ Client           │  │  Client     │  │  + Executor      │
    │ (阿里云百炼)      │  │  (空操作兜底) │  │  + HealthStore   │
    └──────────────────┘  └─────────────┘  └─────────────────┘
```

**与其他模块的对比**：

| 特征 | chat | embedding | rerank |
|------|:---:|:---:|:---:|
| 服务接口方法数 | 4 | 5 | **1** |
| 厂商实现数 | 4 | 3 | **2（1 真 + 1 空）** |
| 抽象基类 | ✅ | ✅ | **❌（直接实现）** |
| 流式支持 | ✅ | ❌ | ❌ |
| 档位机制 | ✅ | ❌ | ❌ |
| 批量子方法 | ❌ | ✅ maxBatchSize | ❌ |
| 独特设计 | 首包探测 | 分批循环 | **去重 + 补位** |

## 三、逐文件深入解析

### 3.1 RerankService（业务层接口）

```java
public interface RerankService {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
```

只有**一个方法**——是整个 infra 层最简洁的服务接口。

**参数语义**：

| 参数 | 含义 | 典型值 |
|------|------|--------|
| `query` | 用户原始问题 | "RAG 是什么？" |
| `candidates` | 向量检索召回的候选文档列表 | 20~50 条 `RetrievedChunk` |
| `topN` | 最终保留条数 | 3~8 条 |
| 返回值 | 按相关性降序排列的精排文档 | `candidates` 中相关性最高的 topN 条 |

**设计原则**：输入 candidates 越多越好（向量检索多召回一些降低漏检），输出 topN 按 LLM 的上下文窗口决定（通常 GPT-4 级别 3~5 条就够了）。

---

### 3.2 RerankClient（厂商级接口）

```java
public interface RerankClient {
    String provider();
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target);
}
```

与 `EmbeddingClient` 和 `ChatClient` 高度一致：`provider()` + 核心业务方法 + `ModelTarget`。

---

### 3.3 RoutingRerankService（路由实现）

```java
@Service
@Primary
public class RoutingRerankService implements RerankService {
    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final Map<String, RerankClient> clientsByProvider;

    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        return executor.executeWithFallback(
            ModelCapability.RERANK,
            selector.selectRerankCandidates(),        // 按 defaultModel+priority 排序
            target -> clientsByProvider.get(target.candidate().getProvider()),
            (client, target) -> client.rerank(query, candidates, topN, target)
        );
    }
}
```

**与 RoutingEmbeddingService 的对比**：

| 维度 | RoutingEmbeddingService | RoutingRerankService |
|------|------------------------|---------------------|
| 方法数 | 4（embed×2 + embedBatch×2） | **1** |
| 指定模型重载 | ✅ embed(text, modelId) | **❌ 只有一个方法** |
| modelId 匹配逻辑 | resolveTarget(modelId) | 不需要 |
| 构造函数 | 同（收集所有 Bean，按 provider 建 Map） | 完全相同 |

rerank 是最简洁的路由实现——没有重载、没有指定模型、没有批量逻辑。

---

### 3.4 BaiLianRerankClient（唯一的真实实现）

这是本模块最核心的文件。它直接实现了 `RerankClient` 接口（没有继承抽象基类），调用阿里云百炼的 `/v1/rerank` DashScope API。

#### 3.4.1 整体流程

```
rerank(query, candidates, topN, target)
    │
    ├─ 1. 输入为空？→ 返回空列表
    │
    ├─ 2. 去重：按 RetrievedChunk.id 去重（保留首次出现）
    │     dedup = [chunk_a, chunk_b, chunk_c, ...]
    │
    ├─ 3. 快捷路径：topN ≤ 0 或 dedup.size() ≤ topN
    │     → 无需调 API，直接返回 dedup
    │     （如果总量已经 ≤ 目标数量，排序没有意义）
    │
    └─ 4. doRerank(query, dedup, topN, target)
          │
          ├─ 构建请求体并发送
          ├─ 解析响应：results[i].index → 映射回原 candidates
          ├─ 组装结果（带 relevance_score）
          └─ 补位：如果 API 返回数量 < topN，从原列表补足
```

#### 3.4.2 请求体结构（DashScope Rerank API）

```json
{
  "model": "gte-rerank",
  "input": {
    "query": "RAG 是什么？",
    "documents": [
      "文档1的文本内容...",
      "文档2的文本内容...",
      "文档3的文本内容..."
    ]
  },
  "parameters": {
    "top_n": 5,
    "return_documents": true
  }
}
```

注意：API 接受的是纯文本数组，不是 `RetrievedChunk` 对象。所以请求时只传 `each.getText()`。

#### 3.4.3 响应解析与映射

API 返回格式：

```json
{
  "output": {
    "results": [
      {"index": 2, "relevance_score": 0.95},
      {"index": 0, "relevance_score": 0.82},
      {"index": 5, "relevance_score": 0.71}
    ]
  }
}
```

**关键设计：index 映射**。API 返回的是文档在输入数组中的索引（index），不是文档 ID。所以需要：

```java
int idx = item.get("index").getAsInt();
RetrievedChunk src = candidates.get(idx);  // 按索引回查原始对象
```

然后用原始对象的元信息（id、docId、docName 等）+ API 返回的 relevance_score 构造新的 `RetrievedChunk`。

#### 3.4.4 补位机制（Padding）

```java
// 如果 API 返回的结果数量 < topN
if (reranked.size() < topN) {
    for (RetrievedChunk c : candidates) {
        if (addedIds.add(c.getId())) {    // 跳过已添加的
            reranked.add(c);              // 保持原始顺序补位
        }
        if (reranked.size() >= topN) {
            break;
        }
    }
}
```

**为什么需要补位？**

Cross-Encoder 模型可能返回的结果数少于 topN（比如 candidates 只有 3 条但 topN=5，或模型内部过滤了一些低分文档）。补位确保返回值始终是 topN 条——RAG 管线后续步骤依赖这个数量假设。

补位时使用**原始 candidates 的顺序**（即向量检索的相似度排序），这是一个合理的兜底策略。

#### 3.4.5 为什么不继承 AbstractOpenAIStyle 基类？

DashScope 的 Rerank API 与 `/v1/embeddings` 和 `/v1/chat/completions` 的协议差异较大：
- 请求结构是 `{ model, input: { query, documents }, parameters: { top_n } }`，不是标准的 messages/input 数组
- 响应解析需要 index 映射回原对象
- 有独特的补位逻辑

如果强行抽象一个 `AbstractOpenAIStyleRerankClient`，钩子方法会非常复杂，收益不大。直接实现 `RerankClient` 接口更清晰。

---

### 3.5 NoopRerankClient（空操作兜底）

```java
@Service
public class NoopRerankClient implements RerankClient {
    public String provider() { return ModelProvider.NOOP.getId(); }

    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates,
                                        int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (topN <= 0 || candidates.size() <= topN) return candidates;
        return candidates.stream().limit(topN).collect(Collectors.toList());
    }
}
```

**什么是 Noop？** Noop = No Operation（空操作）。它不做任何重排序，直接截取前 topN 条返回。

**存在的意义**：

1. **开发/测试环境降级**：没有 Rerank API Key 时，系统仍能跑通（虽然有损精度）
2. **配置熔断**：当 `ModelSelector` 选不到任何健康的 Rerank 模型时，`selectRerankCandidates()` 可能返回空列表。但如果在 candidates 注册表中配置了 NOOP 作为最低 priority 的兜底，就能避免整个 RAG 管线崩溃
3. **架构完整性**：让 `RoutingRerankService` 的 `clientsByProvider` Map 始终有一个可用的条目

**为什么直接截取前 topN 条？**

向量检索的相似度排序已经是"粗排"了，前 topN 条虽然不是 Cross-Encoder 精排的，但也比随机选择好得多。这是一个"优雅降级"（graceful degradation）策略。

---

## 四、完整调用链路

### 4.1 一次典型的 Rerank 调用

```
业务代码（RAG 管线）:
    candidates = vectorSearch.retrieve(query, topK=20)  // 20 条候选
    topN = 5
    result = rerankService.rerank(query, candidates, topN)
    ...
    llmService.chat(prompt + result)  // 用精排结果喂 LLM

    │
    ▼
RoutingRerankService.rerank(query, candidates, 5)
    │
    ├─ 1. selector.selectRerankCandidates()
    │     → ModelSelector
    │        ├─ 获取 rerank 组的配置
    │        ├─ filterAndSortCandidates(candidates, defaultModel)
    │        └─ buildAvailableTargets → 检查断路器
    │     → [gte-rerank(provider=bailian), noop-rerank(provider=noop)]
    │
    ├─ 2. executor.executeWithFallback(RERANK, targets, resolveClient, caller)
    │     │
    │     ├─ 尝试 gte-rerank (bailian):
    │     │   ├─ resolveClient → BaiLianRerankClient
    │     │   ├─ healthStore.allowCall("gte-rerank") → true
    │     │   └─ client.rerank(query, candidates, 5, target)
    │     │        │
    │     │        ├─ candidates.size() = 20 > 5 → 不跳过
    │     │        ├─ 去重：20 → 18（有 2 条重复 chunk）
    │     │        │
    │     │        └─ doRerank(query, 18 docs, 5, target)
    │     │             ├─ POST /v1/rerank
    │     │             │   body: { model:"gte-rerank",
    │     │             │           input:{ query:"RAG 是什么？",
    │     │             │                   documents:["文本1","文本2",...] },
    │     │             │           parameters:{ top_n:5, return_documents:true } }
    │     │             │
    │     │             ├─ 200 OK
    │     │             │   { output: { results: [
    │     │             │       {index:12, relevance_score:0.93},
    │     │             │       {index:3,  relevance_score:0.88},
    │     │             │       {index:7,  relevance_score:0.81},
    │     │             │       {index:15, relevance_score:0.76},
    │     │             │       {index:0,  relevance_score:0.65}
    │     │             │   ]}}
    │     │             │
    │     │             ├─ 映射：index→RetrievedChunk + score
    │     │             ├─ 5 条 ≥ topN=5 → 不需要补位
    │     │             └─ 返回按相关性降序的 5 条
    │     │
    │     │   → markSuccess("gte-rerank")
    │     │   → 返回
    │     │
    │     └─ (noop-rerank 不会被调用)
    │
    └─ 3. 返回 List<RetrievedChunk>（精排后的 5 条）
```

### 4.2 故障转移到 Noop 的降级场景

```
尝试 gte-rerank (bailian):
    ├─ healthStore.allowCall("gte-rerank") → false（断路器 OPEN）
    └─ 跳过

尝试 noop-rerank (noop):
    ├─ resolveClient → NoopRerankClient
    ├─ healthStore.allowCall("noop-rerank") → true（NOOP 不受断路器影响？由配置决定）
    └─ client.rerank(query, candidates, 5, target)
         └─ candidates.stream().limit(5).collect(toList())
              → 返回向量检索的前 5 条（未精排，但可用）
    → markSuccess("noop-rerank")
    → 返回
```

---

## 五、组件职责速查表

| 文件 | 类型 | 一句话职责 |
|------|------|-----------|
| `RerankService` | 接口 | 业务层入口：rerank(query, candidates, topN) |
| `RerankClient` | 接口 | 厂商级接口：provider() + rerank() |
| `RoutingRerankService` | @Service @Primary | 路由实现：委托 ModelSelector + ModelRoutingExecutor 做故障转移 |
| `BaiLianRerankClient` | @Service | **核心实现**：调用 DashScope Rerank API，含去重+index映射+补位 |
| `NoopRerankClient` | @Service | 空操作兜底：不做重排序，直接返回前 topN 条 |

## 六、关键学习要点

### 6.1 为什么 rerank 模块没有抽象基类？

三个原因：
1. **厂商太少**：目前只有一个真实厂商（百炼），没必要引入抽象层
2. **协议差异大**：DashScope Rerank API 的请求/响应结构与标准的 `/v1/embeddings` 和 `/v1/chat/completions` 完全不同（嵌套 input 对象 + parameters + index 映射）
3. **YAGNI 原则**：先直接实现，等第二个厂商出现时再抽象——过早抽象往往增加复杂度而非减少

### 6.2 补位（Padding）为什么用原始顺序而非其他策略？

原始顺序 = 向量检索的余弦相似度排序。这已经是"粗排"了，虽然不如 Cross-Encoder 精确，但也不是随机的。在 API 返回值不足时，用粗排结果补位是最合理的兜底策略。

另一种可选策略是"只返回 API 实际给出的条数"（不补位），但这样会导致：
- RAG 管线后续步骤收到的文档数量不确定
- LLM 收到的上下文信息量减少，回答质量下降

### 6.3 为什么去重要在 rerank 之前做？

向量检索可能返回内容相同但来自不同文档库的 chunk（如 FAQ 库和文档库有交叉引用）。如果不去重，Rerank API 会对重复内容重复打分，浪费 API 调用配额，且可能挤占真正不同的高质量候选。

### 6.4 NoopRerankClient 的 limit(topN) 为什么是对的？

```java
return candidates.stream().limit(topN).collect(Collectors.toList());
```

因为 `candidates` 来自向量检索，已经按相似度从高到低排序。`limit(topN)` 等价于"不精排，直接取向量检索的前 topN 条"。这是与向量检索的语义一致的最优降级策略。

### 6.5 Rerank 的结果为什么需要 index 映射？

Rerank API 的请求只传了文档的**纯文本**，没有传文档 ID 等元信息。API 返回时用 `index`（在输入数组中的位置）来标识文档。所以必须：

```
candidates.get(response.results[i].index) → 取回完整的 RetrievedChunk 元信息
```

这带来一个约束：请求时的文档数组和 `candidates` 列表必须是**同序的**——代码中通过 `for (RetrievedChunk each : candidates)` 遍历保证了这一点。

---

*文档生成时间：基于代码结构分析自动生成。结合 `model/LEARNING.md`、`chat/LEARNING.md`、`embedding/LEARNING.md` 四件套可覆盖 Ragent infra 层的完整知识体系。*
