package com.hjs.study.ragent.framework.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.hjs.study.ragent.framework.convention.Result;
import com.hjs.study.ragent.framework.errorcode.BaseErrorCode;
import com.hjs.study.ragent.framework.exception.AbstractException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Optional;

/**
 * 全局异常处理器
 *
 * <p>
 * 该类基于 {@link RestControllerAdvice} 实现全局异常统一拦截，
 * 用于把 Controller 层以及后续业务处理中抛出的异常转换成统一的 {@link Result} 响应对象
 * </p>
 *
 * <p>
 * 它解决的核心问题是：如果系统中每个接口都自己 try-catch 并手动拼装错误返回，
 * 会导致大量重复代码，而且错误格式容易不一致。通过全局异常处理器可以实现：
 * </p>
 * <ul>
 *   <li>统一异常日志格式</li>
 *   <li>统一接口失败返回结构</li>
 *   <li>避免把底层堆栈信息直接暴露给前端</li>
 *   <li>让业务代码更专注于正常流程处理</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 单个上传文件大小上限
     *
     * <p>
     * 从 Spring Multipart 配置中读取，用于在文件超限时拼装更友好的提示信息
     * </p>
     */
    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String maxFileSize;

    /**
     * 单次上传请求总大小上限
     *
     * <p>
     * 与 {@link #maxFileSize} 配合使用，用于区分“单文件超限”和“整次请求超限”
     * </p>
     */
    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private String maxRequestSize;

    /**
     * 拦截参数验证异常
     *
     * <p>
     * 当请求对象上使用了参数校验注解，例如 {@code @Valid}、{@code @NotBlank}、
     * {@code @NotNull} 等，而实际传入参数不符合要求时，Spring 会抛出
     * {@link MethodArgumentNotValidException}
     * </p>
     *
     * <p>
     * 这里的处理策略是：
     * </p>
     * <ul>
     *   <li>从校验结果中取第一个字段错误</li>
     *   <li>提取其默认错误消息</li>
     *   <li>记录日志</li>
     *   <li>返回统一的客户端错误响应</li>
     * </ul>
     *
     * @param request 当前 HTTP 请求
     * @param ex      参数校验异常
     * @return 统一失败响应
     */
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    @SneakyThrows
    public Result<Void> validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        FieldError firstFieldError = CollectionUtil.getFirst(bindingResult.getFieldErrors());
        String exceptionStr = Optional.ofNullable(firstFieldError)
                .map(FieldError::getDefaultMessage)
                .orElse(StrUtil.EMPTY);
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionStr);
    }

    /**
     * 拦截应用内抛出的异常
     *
     * <p>
     * 这里统一处理项目内部自定义异常体系，也就是继承自 {@link AbstractException} 的异常。
     * 这一类异常通常已经自带明确的业务错误码和错误消息，因此可以直接转换为标准响应
     * </p>
     *
     * <p>
     * 这里还区分了两种日志输出方式：
     * </p>
     * <ul>
     *   <li>如果存在 cause，则直接打印完整异常链，方便定位底层原因</li>
     *   <li>如果没有 cause，则手动拼接前几层堆栈，避免日志过长</li>
     * </ul>
     *
     * @param request 当前 HTTP 请求
     * @param ex      项目内部抽象异常
     * @return 统一失败响应
     */
    @ExceptionHandler(value = {AbstractException.class})
    public Result<Void> abstractException(HttpServletRequest request, AbstractException ex) {
        if (ex.getCause() != null) {
            log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex, ex.getCause());
            return Results.failure(ex);
        }
        StringBuilder stackTraceBuilder = new StringBuilder();
        stackTraceBuilder.append(ex.getClass().getName()).append(": ").append(ex.getErrorMessage()).append("\n");
        StackTraceElement[] stackTrace = ex.getStackTrace();
        for (int i = 0; i < Math.min(5, stackTrace.length); i++) {
            stackTraceBuilder.append("\tat ").append(stackTrace[i]).append("\n");
        }
        log.error("[{}] {} [ex] {} \n\n{}", request.getMethod(), request.getRequestURL().toString(), ex, stackTraceBuilder);
        return Results.failure(ex);
    }

    /**
     * 拦截未登录异常
     *
     * <p>
     * 该异常来自 Sa-Token，表示当前请求没有有效登录态，
     * 或者登录状态已经失效
     * </p>
     *
     * @param request 当前 HTTP 请求
     * @param ex      未登录异常
     * @return 统一失败响应
     */
    @ExceptionHandler(value = NotLoginException.class)
    public Result<Void> notLoginException(HttpServletRequest request, NotLoginException ex) {
        log.warn("[{}] {} [auth] not-login: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "未登录或登录已过期");
    }

    /**
     * 拦截无角色权限异常
     *
     * <p>
     * 该异常通常出现在用户已登录，但不具备接口所需角色权限时。
     * 这里返回的是一个通用的“权限不足”提示，而不是暴露具体角色校验细节
     * </p>
     *
     * @param request 当前 HTTP 请求
     * @param ex      无角色权限异常
     * @return 统一失败响应
     */
    @ExceptionHandler(value = NotRoleException.class)
    public Result<Void> notRoleException(HttpServletRequest request, NotRoleException ex) {
        log.warn("[{}] {} [auth] no-role: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "权限不足");
    }

    /**
     * 拦截文件上传大小超限异常
     *
     * <p>
     * Spring 在处理 multipart 上传时，如果文件或整次请求超过配置上限，
     * 会抛出 {@link MaxUploadSizeExceededException}
     * </p>
     *
     * <p>
     * 这里进一步根据底层 cause 来区分：
     * </p>
     * <ul>
     *   <li>单个文件大小超过限制</li>
     *   <li>整次请求体总大小超过限制</li>
     * </ul>
     *
     * @param request 当前 HTTP 请求
     * @param ex      上传大小超限异常
     * @return 统一失败响应
     */
    @ExceptionHandler(value = MaxUploadSizeExceededException.class)
    public Result<Void> maxUploadSizeExceededException(HttpServletRequest request, MaxUploadSizeExceededException ex) {
        log.warn("[{}] {} [upload] 文件上传大小超限: {}", request.getMethod(), getUrl(request), ex.getMessage());
        String message;
        if (ex.getCause() instanceof IllegalStateException
                && ex.getCause().getCause() instanceof FileSizeLimitExceededException) {
            message = "上传文件大小超过限制，单个文件最大允许 " + maxFileSize;
        } else {
            message = "上传请求大小超过限制，单次请求最大允许 " + maxRequestSize;
        }
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), message);
    }

    /**
     * 拦截未捕获异常
     *
     * <p>
     * 这是兜底异常处理器。当上面的更具体异常类型都没有匹配上时，
     * 会进入这里。它的目标是：
     * </p>
     * <ul>
     *   <li>防止异常继续向外抛出导致默认错误页或非统一响应</li>
     *   <li>确保日志中保留完整异常信息</li>
     *   <li>对前端统一返回通用服务端错误</li>
     * </ul>
     *
     * @param request   当前 HTTP 请求
     * @param throwable 任意未处理异常
     * @return 默认失败响应
     */
    @ExceptionHandler(value = Throwable.class)
    public Result<Void> defaultErrorHandler(HttpServletRequest request, Throwable throwable) {
        log.error("[{}] {} ", request.getMethod(), getUrl(request), throwable);
        return Results.failure();
    }

    /**
     * 获取完整请求地址
     *
     * <p>
     * 如果请求中带有查询参数，则把 queryString 一并拼接进去，
     * 方便日志记录时完整还原请求地址
     * </p>
     *
     * @param request 当前 HTTP 请求
     * @return 完整 URL
     */
    private String getUrl(HttpServletRequest request) {
        if (StrUtil.isBlank(request.getQueryString())) {
            return request.getRequestURL().toString();
        }
        return request.getRequestURL().toString() + "?" + request.getQueryString();
    }
}
