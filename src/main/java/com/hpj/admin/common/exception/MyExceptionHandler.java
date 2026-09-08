package com.hpj.admin.common.exception;

import com.hpj.admin.entity.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * @author huangpeijun
 * @date 2020/3/8
 */
@RestControllerAdvice
public class MyExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(MyExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = exception instanceof ErrorResponse error
                ? error.getBody().getDetail() : "请求参数无效";
        return super.handleExceptionInternal(exception, R.error(status.value(), message), headers, status, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<R<String>> conflict(DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(R.error(409, "数据冲突，无法完成操作"));
    }

    @ExceptionHandler(value = Exception.class)
    public ResponseEntity<R<String>> exceptionHandler(Exception e) {
        log.error("未知异常:", e);
        return ResponseEntity.internalServerError().body(R.error(R.DEFAULT_ERROR_CODE, "服务暂不可用"));
    }
}
