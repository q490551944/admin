package com.hpj.admin.chat.security;

import com.hpj.admin.chat.ChatException;
import com.hpj.admin.chat.ChatError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.chat.ChatRoomService;
import com.hpj.admin.mapper.chat.ChatAttachmentMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

/** 附件原图/缩略图共用会话权限边界；具体存储读取由图片功能提供。 */
@Configuration
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatAttachmentAccessConfiguration implements WebMvcConfigurer {
    private final ChatAttachmentMapper attachments;
    private final ChatRoomService rooms;
    private final ObjectMapper json;
    public ChatAttachmentAccessConfiguration(ChatAttachmentMapper attachments, ChatRoomService rooms, ObjectMapper json) {
        this.attachments = attachments;
        this.rooms = rooms;
        this.json = json;
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws java.io.IOException {
                try {
                    long userId = ChatIdentity.require(SecurityContextHolder.getContext().getAuthentication()).getUserId();
                    String[] parts = request.getRequestURI().substring(request.getContextPath().length()).split("/");
                    long id;
                    try { id = Long.parseLong(parts[parts.length - 2]); }
                    catch (NumberFormatException error) { throw ChatException.notFound(); }
                    var attachment = attachments.selectById(id);
                    if (attachment == null) throw ChatException.notFound();
                    rooms.requireAccess(userId, attachment.getConversationId());
                    return true;
                } catch (ChatException error) {
                    // 资源路由不一定落入聊天控制器的异常处理范围。
                    response.setStatus(error.getStatus());
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    json.writeValue(response.getOutputStream(), ChatError.from(error));
                    return false;
                }
            }
        }).addPathPatterns("/api/chat/v1/attachments/*/content", "/api/chat/v1/attachments/*/thumbnail");
    }
}
