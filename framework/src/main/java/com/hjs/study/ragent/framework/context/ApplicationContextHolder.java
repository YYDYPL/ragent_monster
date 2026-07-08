package com.hjs.study.ragent.framework.context;

import lombok.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.annotation.Annotation;
import java.util.Map;

public class ApplicationContextHolder implements ApplicationContextAware {

    private static ApplicationContext CONTEXT;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        ApplicationContextHolder.CONTEXT = applicationContext;
    }

    public static <T> T getBean(Class<T> clazz){
        return CONTEXT.getBean(clazz);
    }

    public static <T> T getBean(String name, Class<T> clazz){
        return CONTEXT.getBean(name, clazz);
    }

    public static Object getBean(String name){
        return CONTEXT.getBean(name);
    }
    public static <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType) {
        return CONTEXT.findAnnotationOnBean(beanName, annotationType);
    }
    public static <T> Map<String, T> getBeansOfType(Class<T> clazz) {
        return CONTEXT.getBeansOfType(clazz);
    }
    public static ApplicationContext getInstance() {
        return CONTEXT;
    }


}
