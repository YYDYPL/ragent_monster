package com.hjs.study.ragent.framework.idempotent;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentConsume {

    String keyPrefix() default "";

    String key();

    long keyTimeout() default 3600L;
}
