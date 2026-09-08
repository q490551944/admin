package com.hpj.admin.entity;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public class ScriptRequest {
    @NotBlank(message = "expression 不能为空")
    private String expression;
    private Map<String, Object> paramMap;

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public Map<String, Object> getParamMap() {
        return paramMap;
    }

    public void setParamMap(Map<String, Object> paramMap) {
        this.paramMap = paramMap;
    }
}
