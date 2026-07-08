package com.hjs.study.ragent.framework.convention;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChatRequest {

    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();

    private Double temperature;

    private Double topP;

    private Integer topK;

    private Integer maxTokens;

    private Boolean thinking;

    private Boolean enableTools;
}
