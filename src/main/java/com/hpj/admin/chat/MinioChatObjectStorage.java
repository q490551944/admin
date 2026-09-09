package com.hpj.admin.chat;

import com.hpj.admin.common.config.chat.ChatProperties;
import io.minio.*;
import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class MinioChatObjectStorage implements ChatObjectStorage {
    private final ChatProperties properties;
    private volatile MinioClient client;

    public MinioChatObjectStorage(ChatProperties properties) { this.properties = properties; }

    // Lazy construction allows text-only deployments to start without storage credentials.
    private MinioClient client() {
        if (client == null) synchronized (this) {
            if (client == null) {
                var config = properties.getAttachment();
                if (config.getAccessKey().isBlank() || config.getSecretKey().isBlank())
                    throw new ChatException(503, "STORAGE_UNAVAILABLE", "图片存储尚未配置");
                String endpoint = config.getEndpoint();
                if (config.isSecure()) {
                    if (endpoint.regionMatches(true, 0, "http://", 0, 7)) endpoint = "https://" + endpoint.substring(7);
                    else if (!endpoint.contains("://")) endpoint = "https://" + endpoint;
                }
                client = MinioClient.builder().endpoint(endpoint)
                        .credentials(config.getAccessKey(), config.getSecretKey())
                        .httpClient(new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                                .readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
                                .callTimeout(30, TimeUnit.SECONDS).build()).build();
            }
        }
        return client;
    }

    @Override public void put(String bucket, String key, byte[] content, String type) {
        try {
            client().putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new ByteArrayInputStream(content), content.length, -1).contentType(type).build());
        } catch (Exception error) { throw unavailable(error); }
    }

    @Override public byte[] read(String bucket, String key, int maximumBytes) {
        try (var stream = client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] result = stream.readNBytes(maximumBytes + 1);
            if (result.length > maximumBytes) throw new IllegalStateException("Stored image exceeds limit");
            return result;
        } catch (Exception error) { throw unavailable(error); }
    }

    @Override public void delete(String bucket, String key) {
        try { client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception error) { throw unavailable(error); }
    }

    private ChatException unavailable(Exception error) {
        if (error instanceof ChatException chat) return chat;
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Chat object storage operation failed", error);
        return new ChatException(503, "STORAGE_UNAVAILABLE", "图片存储暂不可用，请稍后重试");
    }
}
