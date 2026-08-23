# LightRAG 多知识库生产部署

本目录采用“共享 Neo4j/PostgreSQL 存储 + 每知识库一个 LightRAG 实例”。每个实例的
`WORKSPACE`、`POSTGRES_WORKSPACE`、`NEO4J_WORKSPACE` 都固定为 `kb_<kbId>`，后端只通过
`http://ragent-lightrag-kb-<kbId>:9621` 访问，不接受数据库或请求参数提供 URL。

## 安全门槛

- `LIGHTRAG_IMAGE` 必须是正式稳定版并带 `@sha256:` digest，禁止 `latest`。
- 该版本必须已修复 `GHSA-mmg5-8x8q-v934` 和 `GHSA-2hjx-23vw-wvw4`；否则后端
  `LIGHTRAG_SECURITY_APPROVED` 必须保持 `false`，图谱通道不会启用。
- 必须配置 `LIGHTRAG_API_KEY`，API prefix 必须为空，实例不映射任何宿主端口。
- 模型、Embedding 维度、API 版本或镜像 digest 变化会改变配置指纹，workspace 自动暂停，
  必须从 PostgreSQL 真源全量重建后再启用。

## 启动

先复制 `.env.example` 为受保护的 `.env`，填写 digest、密码和模型密钥。创建共享网络及存储：

```bash
docker network inspect ragent-internal >/dev/null 2>&1 || docker network create ragent-internal
docker compose --env-file .env -f graph-storage.compose.yaml up -d
```

为每个已启用图谱的知识库启动独立实例（最多 20 个）：

```bash
KB_ID=1934567890123456789 docker compose --env-file .env \
  -p ragent-kg-1934567890123456789 \
  -f lightrag-instance.compose.yaml up -d
```

后端设置 `rag.graph.type=lightrag` 后，先调用
`POST /admin/kg/workspaces/{kbId}/validate`，校验健康、安全和配置指纹；再调用 `/enable`。
启用操作会自动把 workspace 置为 `REBUILDING` 并从 PostgreSQL 真源生成全量任务；只有全部任务
成功且配置指纹一致后才会切换为 `HEALTHY` 并参与检索。写入接口返回的 `track_id` 会持久化并
持续轮询，只有所有文档状态为 `processed` 才会将投影标记成功。手工调用
`POST /admin/projections/rebuild` 只用于重新执行全量重建。

关闭知识库图谱时先调用 `/disable`，等待 workspace 进入 `DISABLED` 后再停止对应 Compose
project。PostgreSQL 是唯一真源，LightRAG/Neo4j 数据可随时清空后按库重建。
