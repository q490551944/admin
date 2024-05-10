package com.hpj.admin.entity;

import java.sql.Timestamp;

public class R<T>{

    private int status;
    private T data;
    private long timestamp;

    public static final Integer DEFAULT_ERROR_CODE = 500;
    public static final Integer DEFAULT_OK_CODE = 1000;

    public R() {
        this.timestamp = System.currentTimeMillis();
    }

    public R(T data) {
        this.data = data;
    }

    public R(int status, T data) {
        this.status = status;
        this.data = data;
    }

    public static <T> R<T> ok(T data) {
        return new R<T>(data);
    }

    public static <T> R<T> error(int status, T data) {
        return new R<T>(status, data);
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
