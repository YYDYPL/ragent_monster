package com.hjs.study.ragent.framework.trace;

public interface RagStreamTraceSupport {

    StreamSpan beginStreamNode(String name, String type);

    interface StreamSpan {

        /**
         * 调用线程同步部分结束时调用，把 nodeId 从 NODE_STACK 弹出
         * 不会 finish 节点
         *
         * <p>
         * 它的作用类似“结束当前线程中的父子关系绑定”，
         * 但不会真正把 trace 节点标记为成功或失败。
         * 因为真正的完成时刻还要等异步流式处理结束后才能确定
         * </p>
         */
        void detach();

        /**
         * 异步线程上：onComplete 时调用，CAS 保证幂等
         *
         * <p>
         * 当流正常结束时调用，用于把节点标记为成功完成。
         * 使用 CAS 的目的是避免 onComplete / onError / cancel 等多条路径重复收尾
         * </p>
         */
        void finishSuccess();

        /**
         * 异步线程上：onError 时调用，CAS 保证幂等
         *
         * <p>
         * 当流式执行异常终止时调用，把异常信息附加到 trace 节点上，
         * 方便后续排查是模型调用失败、网络断开还是消费端处理失败
         * </p>
         *
         * @param error 导致流失败的异常
         */
        void finishError(Throwable error);

        /**
         * cancel 路径：如节点尚未 finish，则按取消语义收尾，避免 RUNNING 悬挂
         *
         * <p>
         * 某些场景下既不是正常完成，也不是异常失败，而是用户主动取消、
         * 浏览器断开连接或上游手动中止。此时如果不显式收尾，
         * trace 中可能一直保留 RUNNING 状态，造成“悬挂节点”
         * </p>
         */
        void finishCancelledIfRunning();
    }

    /**
     * 不开启 trace 时使用的空实现，所有方法 no-op
     *
     * <p>
     * 用于关闭追踪功能时兜底返回，调用方仍可安全调用各方法，
     * 但不会产生任何实际 trace 记录
     * </p>
     */
    StreamSpan NOOP_SPAN = new StreamSpan() {
        @Override public void detach() {}
        @Override public void finishSuccess() {}
        @Override public void finishError(Throwable error) {}
        @Override public void finishCancelledIfRunning() {}
    };
}
