#!/usr/bin/env sh
set -eu

: "${ELASTIC_PASSWORD:?ELASTIC_PASSWORD is required}"
curl --fail --silent --show-error \
  --cacert /usr/share/elasticsearch/config/certs/ca/ca.crt \
  --user "elastic:${ELASTIC_PASSWORD}" \
  --header 'Content-Type: application/json' \
  --request PUT https://localhost:9200/_index_template/rag_keyword_v2 \
  --data '{
    "index_patterns":["rag_keyword_v2_*"],
    "priority":200,
    "version":2,
    "template":{
      "settings":{"number_of_shards":1,"number_of_replicas":0},
      "mappings":{"dynamic":"strict","properties":{
        "kb_id":{"type":"keyword"},
        "collection_name":{"type":"keyword"},
        "doc_id":{"type":"keyword"},
        "chunk_id":{"type":"keyword"},
        "chunk_index":{"type":"integer"},
        "content":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
        "outline":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
        "block_type":{"type":"keyword"},
        "content_hash":{"type":"keyword"},
        "content_version":{"type":"long"},
        "indexed_at":{"type":"date"}
      }}
    }
  }'
