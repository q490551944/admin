package com.hpj.admin.controller.chat;

import com.hpj.admin.chat.ChatRoomService;
import com.hpj.admin.chat.ConversationView;
import com.hpj.admin.chat.security.ChatIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 房间 REST 入口；操作人统一从已认证的服务端身份中取得。 */
@RestController
@RequestMapping("/api/chat/v1")
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatRoomController {
    private final ChatRoomService rooms;

    public ChatRoomController(ChatRoomService rooms) { this.rooms = rooms; }

    /** 返回当前用户可见的活动会话及摘要。 */
    @GetMapping("/conversations")
    public List<ConversationView> conversations(Authentication authentication) {
        return rooms.visible(ChatIdentity.require(authentication).getUserId());
    }

    /** 创建公共房间，成功返回 201 和新房间的展示信息。 */
    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView create(Authentication authentication, @RequestBody CreateRoom request) {
        // 请求仅接收名称，客户端附带的 ownerId 等字段不能指定房主身份。
        return rooms.create(ChatIdentity.require(authentication).getUserId(), request.name());
    }

    /** 房主解散房间，首次成功及重复解散均返回 204。 */
    @DeleteMapping("/rooms/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dissolve(Authentication authentication, @PathVariable long id) {
        rooms.dissolve(ChatIdentity.require(authentication).getUserId(), id);
    }

    public record CreateRoom(String name) {}
}
