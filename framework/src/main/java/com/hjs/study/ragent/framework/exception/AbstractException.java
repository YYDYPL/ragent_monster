package com.hjs.study.ragent.framework.exception;

import com.hjs.study.ragent.framework.errorcode.IErrorCode;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Getter
public class AbstractException extends RuntimeException {


    public final String errorCode;

    public final String errorMessage;

    public AbstractException(String errorMessage,Throwable throwable, IErrorCode errorCode) {
        super(errorMessage, throwable);
        this.errorCode = errorCode.code();
        this.errorMessage = Optional.ofNullable(StringUtils.hasLength(errorMessage)? errorMessage:null).orElse(errorCode.message());
    }


}
