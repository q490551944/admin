package com.hpj.admin.common.exception;

import com.hpj.admin.entity.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * @author huangpeijun
 * @date 2020/3/8
 */
@RestControllerAdvice
public class MyExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(MyExceptionHandler.class);

    @ResponseStatus(HttpStatus.OK)
    @ExceptionHandler(value = Exception.class)
    public R exceptionHandler(Exception e) {
        log.error("未知异常:", e);
        return R.error(R.DEFAULT_ERROR_CODE, e.getMessage());
    }
}
