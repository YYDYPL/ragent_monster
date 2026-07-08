package com.hjs.study.ragent.framework.exception;

import com.hjs.study.ragent.framework.errorcode.BaseErrorCode;
import com.hjs.study.ragent.framework.errorcode.IErrorCode;

import java.util.Optional;

/**
 * 服务端运行异常
 *
 * <p>
 * 表示请求进入系统之后，在当前服务内部执行过程中出现了不符合业务预期的异常。
 * 这类异常强调的是：请求本身不一定有问题，但系统内部在处理时失败了
 * </p>
 *
 * <p>
 * 常见场景例如：
 * </p>
 * <ul>
 *   <li>业务状态校验不通过，例如订单状态不允许重复支付</li>
 *   <li>查询到的数据不完整，无法继续执行业务流程</li>
 *   <li>系统内部出现逻辑分支缺失或流程中断</li>
 *   <li>某个本地服务组件执行失败，但不属于远程依赖异常</li>
 * </ul>
 *
 * <p>
 * 这类异常通常会被全局异常处理器转换成服务端错误响应，
 * 同时记录详细日志供后端排查
 * </p>
 */
public class ServiceException extends AbstractException {

    /**
     * 使用默认服务端错误码创建异常
     *
     * @param message 自定义错误提示
     */
    public ServiceException(String message) {
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    /**
     * 仅根据错误码创建服务端异常
     *
     * <p>
     * 当错误文案已经在错误码枚举中定义好时，可以直接使用这个构造器
     * </p>
     *
     * @param errorCode 业务错误码定义
     */
    public ServiceException(IErrorCode errorCode) {
        this(null, errorCode);
    }

    /**
     * 自定义消息 + 自定义错误码
     *
     * @param message   自定义错误提示
     * @param errorCode 业务错误码定义
     */
    public ServiceException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /**
     * 最完整的服务端异常构造器
     *
     * <p>
     * 与父类不同的是，这里在调用 {@code super(...)} 之前先做了一层 message 兜底：
     * 如果调用方没有传 message，就直接使用 errorCode 中定义的默认文案。
     * 这样可以确保 RuntimeException 本身持有的 message 也尽量不是空值，
     * 便于日志打印和调试排查
     * </p>
     *
     * @param message   自定义错误提示
     * @param throwable 原始异常
     * @param errorCode 业务错误码定义
     */
    public ServiceException(String message, Throwable throwable, IErrorCode errorCode) {
        super(Optional.ofNullable(message).orElse(errorCode.message()), throwable, errorCode);
    }

    @Override
    public String toString() {
        // 输出统一结构，方便服务端异常在日志平台按 code/message 维度快速过滤
        return "ServiceException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}