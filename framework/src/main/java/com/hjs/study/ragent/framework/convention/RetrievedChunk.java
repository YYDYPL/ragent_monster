package com.hjs.study.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrievedChunk {

    private String id;

    private String text;

    private Float score;
}
