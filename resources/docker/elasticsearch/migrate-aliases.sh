#!/usr/bin/env bash
set -euo pipefail

: "${ELASTICSEARCH_URL:?Set ELASTICSEARCH_URL, for example https://localhost:9200}"
: "${ELASTICSEARCH_API_KEY:?Set a least-privilege Elasticsearch API key}"
: "${ELASTICSEARCH_CA:?Set the Elasticsearch CA PEM path}"
: "${TARGET_INDEX:?Set the new versioned physical index}"

mode="${1:-}"
if [[ ! "${TARGET_INDEX}" =~ ^rag_keyword_v2_[0-9]{6}$ ]]; then
  echo "TARGET_INDEX must match rag_keyword_v2_NNNNNN" >&2
  exit 2
fi
if [[ "${mode}" != "prepare" && "${mode}" != "activate" ]]; then
  echo "usage: $0 prepare|activate" >&2
  exit 2
fi

curl_args=(--fail --silent --show-error --cacert "${ELASTICSEARCH_CA}"
  -H "Authorization: ApiKey ${ELASTICSEARCH_API_KEY}"
  -H 'Content-Type: application/json')

if ! curl "${curl_args[@]}" --head "${ELASTICSEARCH_URL}/${TARGET_INDEX}" >/dev/null 2>&1; then
  curl "${curl_args[@]}" -X PUT "${ELASTICSEARCH_URL}/${TARGET_INDEX}" -d '{}'
fi

if [[ "${mode}" == "prepare" ]]; then
  curl "${curl_args[@]}" -X POST "${ELASTICSEARCH_URL}/_aliases" -d "
  {\"actions\":[
    {\"remove\":{\"index\":\"rag_keyword_v2_*\",\"alias\":\"rag_keyword_write\",\"must_exist\":false}},
    {\"add\":{\"index\":\"${TARGET_INDEX}\",\"alias\":\"rag_keyword_write\",\"is_write_index\":true}}
  ]}"
  echo "write alias now targets ${TARGET_INDEX}; restart with RAG_ES_PHYSICAL_INDEX=${TARGET_INDEX}, then rebuild"
else
  curl "${curl_args[@]}" -X POST "${ELASTICSEARCH_URL}/_aliases" -d "
  {\"actions\":[
    {\"remove\":{\"index\":\"rag_keyword_v2_*\",\"alias\":\"rag_keyword_read\",\"must_exist\":false}},
    {\"add\":{\"index\":\"${TARGET_INDEX}\",\"alias\":\"rag_keyword_read\"}}
  ]}"
  echo "read alias atomically activated ${TARGET_INDEX}"
fi
