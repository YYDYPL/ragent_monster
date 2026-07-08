package com.hjs.study.ragent.framework.exception;

import com.hjs.study.ragent.framework.errorcode.BaseErrorCode;
import com.hjs.study.ragent.framework.errorcode.IErrorCode;

public class ClientException extends AbstractException{
    public ClientException(IErrorCode errorCode) {
        this(null, null, errorCode);
    }

    public ClientException(String message) {
        this(message, null, BaseErrorCode.CLIENT_ERROR);
    }

    public ClientException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    public ClientException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }


    @Override
    public String toString() {
        // toString 采用简洁结构，便于日志直接输出时快速定位错误码与核心信息
        return "ClientException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }


}
