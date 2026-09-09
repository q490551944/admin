package com.hpj.admin.chat;

import com.hpj.admin.common.config.chat.ChatProperties;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class ChatObjectStorageTest {
    private HttpUrl endpoint(String endpoint, boolean secure) {
        var properties = new ChatProperties();
        var storage = properties.getAttachment();
        storage.setEndpoint(endpoint);
        storage.setSecure(secure);
        storage.setAccessKey("test-access");
        storage.setSecretKey("test-secret");
        var client = ReflectionTestUtils.invokeMethod(new MinioChatObjectStorage(properties), "client");
        return (HttpUrl) ReflectionTestUtils.getField(ReflectionTestUtils.getField(client, "asyncClient"), "baseUrl");
    }

    @Test void secureFlagUpgradesHttpAndBareHostEndpoints() {
        assertThat(endpoint("http://127.0.0.1:9000", true).toString()).isEqualTo("https://127.0.0.1:9000/");
        assertThat(endpoint("minio.internal", true).scheme()).isEqualTo("https");
    }

    @Test void explicitHttpsIsNeverDowngradedAndLocalHttpRemainsSupported() {
        assertThat(endpoint("https://minio.internal:9443", false).toString()).isEqualTo("https://minio.internal:9443/");
        assertThat(endpoint("http://127.0.0.1:9000", false).scheme()).isEqualTo("http");
    }
}
