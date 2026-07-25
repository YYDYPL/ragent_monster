package com.hjs.study.ragent.infra.chat;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class ForwardingStreamCallback implements StreamCallback{

    private final StreamCallback delegate;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean firstContentSeen = new AtomicBoolean(false);

    protected ForwardingStreamCallback(StreamCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public final void onContent(String content){
        if (firstContentSeen.compareAndSet(false, true)){
            try{
                onFirstContent();
            }catch (Throwable ex){
                //
            }

            delegate.onContent(content);
        }
    }
}
