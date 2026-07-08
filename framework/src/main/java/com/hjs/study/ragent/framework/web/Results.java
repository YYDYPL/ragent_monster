package com.hjs.study.ragent.framework.web;

import com.hjs.study.ragent.framework.convention.Result;
import com.hjs.study.ragent.framework.errorcode.BaseErrorCode;
import com.hjs.study.ragent.framework.exception.AbstractException;

import java.util.Optional;

/**
 * 全局统一返回结果构造工具类
 *
 * <p>
 * 该类用于快速构建项目中统一的 {@link Result} 返回对象，
 * 避免在各个 Controller 或异常处理器中重复编写
 * {@code new Result<>().setCode(...).setMessage(...).setData(...)} 这类样板代码
 * </p>
 *
 * <p>
 * 它可以理解为 Result 的“工厂类”或“快捷构造器”，
 * 专门负责生成常见的成功响应和失败响应
 * </p>
 */
public final class Results {

    /**
     * 构造成功响应
     *
     * <p>
     * 适用于接口调用成功，但没有具体返回数据的场景，
     * 例如删除成功、操作成功、状态修改成功等
     * </p>
     *
     * @return 不带 data 的成功响应
     */
    public static Result<Void> success() {
        return new Result<Void>()
                .setCode(Result.SUCCESS_CODE);
    }

    /**
     * 构造带返回数据的成功响应
     *
     * <p>
     * 适用于接口调用成功且需要返回业务数据的场景，
     * 例如查询详情、分页列表、创建后返回结果等
     * </p>
     *
     * @param data 返回给前端的业务数据
     * @return 带业务数据的成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<T>()
                .setCode(Result.SUCCESS_CODE)
                .setData(data);
    }

    /**
     * 构建服务端失败响应
     *
     * <p>
     * 这是最通用的默认失败响应构造方式。
     * 当系统出现未明确分类的服务端错误时，可以直接返回这个结果
     * </p>
     *
     * @return 默认服务端失败响应
     */
    public static Result<Void> failure() {
        return new Result<Void>()
                .setCode(BaseErrorCode.SERVICE_ERROR.code())
                .setMessage(BaseErrorCode.SERVICE_ERROR.message());
    }

    /**
     * 通过 {@link AbstractException} 构建失败响应
     *
     * <p>
     * 适用于项目内部已定义好的异常体系。
     * 该方法会优先从异常对象中读取错误码和错误消息，
     * 若异常中某个值为空，则退回到默认的服务端错误码和错误消息
     * </p>
     *
     * @param abstractException 项目内部抽象异常
     * @return 基于异常信息构建的失败响应
     */
    static Result<Void> failure(AbstractException abstractException) {
        String errorCode = Optional.ofNullable(abstractException.getErrorCode())
                .orElse(BaseErrorCode.SERVICE_ERROR.code());
        String errorMessage = Optional.ofNullable(abstractException.getErrorMessage())
                .orElse(BaseErrorCode.SERVICE_ERROR.message());
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }

    /**
     * 通过 errorCode、errorMessage 构建失败响应
     *
     * <p>
     * 适用于需要手动指定错误码和错误消息的场景，
     * 例如全局异常处理器在处理第三方异常、参数错误或权限错误时，
     * 可以直接调用本方法返回标准失败结构
     * </p>
     *
     * @param errorCode    错误码
     * @param errorMessage 错误消息
     * @return 自定义失败响应
     */
    static Result<Void> failure(String errorCode, String errorMessage) {
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }
}
