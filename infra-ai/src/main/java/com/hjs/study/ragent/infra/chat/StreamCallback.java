package com.hjs.study.ragent.infra.chat;

public interface StreamCallback {

    void onContent(String content);

    default void onThinking(String content){

    }

    void onComplete();

    void onError(Throwable error);
}
