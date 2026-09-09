package com.hpj.admin.controller.chat;

import com.hpj.admin.chat.*;
import com.hpj.admin.chat.security.ChatIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatAttachmentController {
    private final ChatAttachmentService service;
    public ChatAttachmentController(ChatAttachmentService service) { this.service = service; }

    @PostMapping("/api/chat/v1/conversations/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentView prepare(Authentication auth, @PathVariable long id, @RequestBody ChatAttachmentService.Prepare request) {
        return service.prepare(ChatIdentity.require(auth).getUserId(), id, request);
    }

    @PutMapping("/api/chat/v1/attachments/{id}/upload")
    public AttachmentView upload(Authentication auth, @PathVariable long id, HttpServletRequest request) throws IOException {
        return service.upload(ChatIdentity.require(auth).getUserId(), id, request.getInputStream(), request.getContentLengthLong());
    }

    @GetMapping("/api/chat/v1/attachments/{id}/content")
    public ResponseEntity<byte[]> content(Authentication auth, @PathVariable long id) { return read(auth, id, false); }

    @GetMapping("/api/chat/v1/attachments/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(Authentication auth, @PathVariable long id) { return read(auth, id, true); }

    private ResponseEntity<byte[]> read(Authentication auth, long id, boolean thumbnail) {
        var content = service.content(ChatIdentity.require(auth).getUserId(), id, thumbnail);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline").body(content.bytes());
    }
}
