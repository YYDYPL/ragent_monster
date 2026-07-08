package com.hjs.study.ragent.framework.exception;

import com.hjs.study.ragent.framework.errorcode.BaseErrorCode;
import com.hjs.study.ragent.framework.errorcode.IErrorCode;

/**
 * 远程服务调用异常
 *
 * <p>
 * 表示当前系统在调用外部系统、第三方服务、下游微服务时发生失败。
 * 这类异常的核心特点是：错误点不一定在当前服务本身，
 * 而是在“与外部依赖交互”的过程中产生
 * </p>
 *
 * <p>
 * 典型场景例如：
 * </p>
 * <ul>
 *   <li>调用支付服务失败</li>
 *   <li>请求大模型接口超时或返回异常</li>
 *   <li>访问对象存储、消息队列、检索服务时网络中断</li>
 *   <li>下游系统返回非预期状态码或错误数据</li>
 * </ul>
 *
 * <p>
 * 比如订单调用支付失败，向上抛出的异常就应该是远程服务调用异常，
 * 这样可以帮助业务层快速区分：这是“自己业务逻辑错了”，还是“外部依赖出问题了”
 * </p>
 */
public class RemoteException extends AbstractException {

    /**
     * 使用默认远程调用错误码创建异常
     *
     * @param message 自定义错误提示
     */
    public RemoteException(String message) {
        this(message, null, BaseErrorCode.REMOTE_ERROR);
    }

    /**
     * 自定义消息 + 自定义远程错误码
     *
     * @param message   自定义错误提示
     * @param errorCode 业务错误码定义
     */
    public RemoteException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /**
     * 最完整的远程调用异常构造器
     *
     * <p>
     * 适用于封装底层 HTTP / RPC / SDK 抛出的原始异常，
     * 同时为上层业务补充更明确的错误语义
     * </p>
     *
     * @param message   自定义错误提示
     * @param throwable 原始异常
     * @param errorCode 业务错误码定义
     */
    public RemoteException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }

    @Override
    public String toString() {
        // 保留最关键的 code 和 message，方便远程调用失败时快速做日志检索
        return "RemoteException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}