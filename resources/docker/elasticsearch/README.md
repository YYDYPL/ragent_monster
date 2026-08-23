# Elasticsearch 8.19.19 + IK production baseline

1. Copy `.env.example`, pin `ELASTICSEARCH_IMAGE` by digest, and fill the verified IK archive SHA-256.
2. Create the shared internal network once: `docker network create ragent-internal`.
3. Start `search-stack.compose.yaml`; run `create-index-template.sh` once with the bootstrap password, then create the least-privilege application API key with `create-api-key.sh`. Mount the CA and inject only that API key into the application.
4. Keep `rag.keyword.type=none` until the full rebuild has completed and the projection difference is zero.

For a mapping migration, build the new template/application first, set `TARGET_INDEX` to the next six-digit physical index, and run `migrate-aliases.sh prepare`. Restart the application with that physical index, submit an Elasticsearch rebuild from the “搜索投影” page, and wait for zero pending/blocked tasks. Run reconciliation and search/delete fault checks, then execute `migrate-aliases.sh activate`. Activation changes the read alias atomically; old indexes remain recoverable until the observation window ends.

At 02:00 every night the application pages through a composite aggregation over document version,
chunk identity and content hash, and requeues only divergent documents. Monday's full source replay is a
separate safety net; do not disable it merely because the nightly difference count is normally zero.

Never expose port 9200 publicly, use `latest`, download plugins at container startup, or delete an old physical index before backup and post-activation validation.
