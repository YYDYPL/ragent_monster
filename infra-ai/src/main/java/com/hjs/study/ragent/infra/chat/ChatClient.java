package com.hjs.study.ragent.infra.chat;

import com.hjs.study.ragent.framework.convention.ChatRequest;
import com.hjs.study.ragent.infra.model.ModelTarget;

public interface ChatClient {

    String provider();

    String chat(ChatRequest request, ModelTarget target);

    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target);

}
