#!/usr/bin/env sh
set -eu

: "${ELASTIC_PASSWORD:?ELASTIC_PASSWORD is required}"
curl --fail --silent --show-error \
  --cacert /usr/share/elasticsearch/config/certs/ca/ca.crt \
  --user "elastic:${ELASTIC_PASSWORD}" \
  --header 'Content-Type: application/json' \
  --request POST https://localhost:9200/_security/api_key \
  --data '{"name":"ragent-keyword","role_descriptors":{"ragent_keyword":{"cluster":["monitor"],"index":[{"names":["rag_keyword_v2_*","rag_keyword_read","rag_keyword_write"],"privileges":["read","write","create_index","manage"]}]}}}'
