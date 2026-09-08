package com.hpj.admin.chat;

import com.hpj.admin.chat.security.ChatAccounts;
import com.hpj.admin.common.websocket.WebSocketConfig;
import com.hpj.admin.controller.WebsocketController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ChatTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/chat-test.yml", "chat.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:chat_disabled;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@Import({ChatDisabledTest.LegacyProbe.class, WebSocketConfig.class, WebsocketController.class})
class ChatDisabledTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @RestController
    static class LegacyProbe {
        @PostMapping("/sys/users/probe") String probe() { return "legacy"; }
    }

    @Test void disabledFeatureKeepsLegacyRoutesAndDemoAssetsButDoesNotExposeChatApis() throws Exception {
        assertThat(context.getBeansOfType(ChatRoomService.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatAccounts.class)).isEmpty();
        assertThat(context.getBeansOfType(WebSocketConfig.class)).hasSize(1);
        mvc.perform(post("/sys/users/probe")).andExpect(status().isOk()).andExpect(content().string("legacy"));
        mvc.perform(get("/chat/index.html")).andExpect(status().isOk());
        mvc.perform(get("/api/chat/v1/conversations")).andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/v1/rooms").contentType("application/json").content("{\"name\":\"No room\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/logout")).andExpect(status().isNotFound());
    }
}
