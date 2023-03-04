package com.hpj.admin.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * @author huangpeijun
 * @date 2020/4/18
 */
public enum Sex implements IEnum<String> {
    /**
     * 男人
     */
    man(1, "男人"),
    /**
     * 女人
     */
    women(2, "女人");

    private int key;

    private String value;

    Sex(int key, String value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }

    @JsonValue
    public int getOrdinal() {
        return key;
    }
}