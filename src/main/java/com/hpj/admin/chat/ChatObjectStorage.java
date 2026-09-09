package com.hpj.admin.chat;

/** Object storage operations are idempotent for the same server-generated key. */
public interface ChatObjectStorage {
    void put(String bucket, String key, byte[] content, String contentType);
    byte[] read(String bucket, String key, int maximumBytes);
    void delete(String bucket, String key);
}
