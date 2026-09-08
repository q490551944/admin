package com.hpj.admin.controller.chat;

import com.hpj.admin.chat.ChatError;
import com.hpj.admin.chat.ChatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.hpj.admin.controller.chat")
public class ChatExceptionAdvice extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatExceptionAdvice.class);

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ChatError> business(ChatException error) {
        return ResponseEntity.status(error.getStatus()).body(ChatError.from(error));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpStatusCode responseStatus = status.value() == 400 ? HttpStatus.UNPROCESSABLE_ENTITY : status;
        return super.handleExceptionInternal(error, new ChatError("INVALID_REQUEST", "请求参数或 HTTP 方法无效"),
                headers, responseStatus, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ChatError> unexpected(Exception error) {
        log.error("Chat request failed", error);
        return ResponseEntity.internalServerError().body(new ChatError("INTERNAL_ERROR", "服务暂不可用"));
    }
}
