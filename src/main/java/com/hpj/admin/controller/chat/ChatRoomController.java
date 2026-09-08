package com.hpj.admin.controller.chat;

import com.hpj.admin.chat.ChatRoomService;
import com.hpj.admin.chat.ConversationView;
import com.hpj.admin.chat.security.ChatIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/chat/v1")
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatRoomController {
    private final ChatRoomService rooms;

    public ChatRoomController(ChatRoomService rooms) { this.rooms = rooms; }

    @GetMapping("/conversations")
    public List<ConversationView> conversations(Authentication authentication) {
        return rooms.visible(ChatIdentity.require(authentication).getUserId());
    }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView create(Authentication authentication, @RequestBody CreateRoom request) {
        // ownerId and other unknown input fields are not part of the DTO and cannot set identity.
        return rooms.create(ChatIdentity.require(authentication).getUserId(), request.name());
    }

    @DeleteMapping("/rooms/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dissolve(Authentication authentication, @PathVariable long id) {
        rooms.dissolve(ChatIdentity.require(authentication).getUserId(), id);
    }

    public record CreateRoom(String name) {}
}
