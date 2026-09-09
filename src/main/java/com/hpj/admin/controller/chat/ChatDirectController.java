package com.hpj.admin.controller.chat;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.hpj.admin.chat.*;
import com.hpj.admin.chat.security.ChatIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/chat/v1")
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatDirectController {
    private final ChatDirectService direct;
    public ChatDirectController(ChatDirectService direct) { this.direct = direct; }

    @GetMapping("/users")
    public List<ChatUserView> users(Authentication authentication, @RequestParam(defaultValue = "") String query) {
        return direct.search(ChatIdentity.require(authentication).getUserId(), query);
    }

    /** 新建和重复打开均返回 200；发起人只取服务端 Session。 */
    @PostMapping("/direct-conversations")
    public ConversationView open(Authentication authentication, @RequestBody OpenDirect request) {
        JsonNode peer = request.peerUserId();
        if (peer != null && (peer.isIntegralNumber() || peer.isTextual())) {
            try {
                return direct.open(ChatIdentity.require(authentication).getUserId(), Long.parseLong(peer.asText()));
            } catch (NumberFormatException ignored) {}
        }
        throw new ChatException(422, "INVALID_PEER", "请选择其他有效员工");
    }

    public record OpenDirect(@JsonAlias("peer_user_id") JsonNode peerUserId) {}
}
