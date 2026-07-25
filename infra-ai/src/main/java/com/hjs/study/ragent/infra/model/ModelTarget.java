package com.hjs.study.ragent.infra.model;

import com.hjs.study.ragent.infra.config.AIModelProperties;

public record ModelTarget(
        String id,
        AIModelProperties.ModelCandidate candidate,
        AIModelProperties.ProviderConfig provider

) {
}
