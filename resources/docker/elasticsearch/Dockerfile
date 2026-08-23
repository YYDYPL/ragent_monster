ARG ELASTICSEARCH_IMAGE
FROM ${ELASTICSEARCH_IMAGE}

ARG ELASTIC_VERSION=8.19.19
ARG IK_DOWNLOAD_URL=https://get.infini.cloud/elasticsearch/analysis-ik/8.19.19
ARG IK_SHA256

USER root
RUN test -n "${IK_SHA256}" \
    && curl --fail --silent --show-error --location "${IK_DOWNLOAD_URL}" --output /tmp/analysis-ik.zip \
    && echo "${IK_SHA256}  /tmp/analysis-ik.zip" | sha256sum --check --strict \
    && /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/analysis-ik.zip \
    && rm -f /tmp/analysis-ik.zip \
    && chown -R elasticsearch:root /usr/share/elasticsearch/plugins

USER elasticsearch
