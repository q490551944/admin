package com.hpj.admin.common.exception;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * @author huangpeijun
 * @date 2020/3/8
 */
@ControllerAdvice
public class MyExceptionHandler {

    @ExceptionHandler(value = Exception.class)
    public String exceptionHandler(Exception e) {
        System.out.println("未知异常 :" + e);
        return e.getMessage();
    }
}
