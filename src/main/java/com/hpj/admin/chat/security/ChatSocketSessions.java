package com.hpj.admin.chat.security;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Checks outbound delivery too, so an expired idle connection cannot keep receiving messages. */
public class ChatSocketSessions {
    private final ConcurrentHashMap<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final ChatAccounts accounts;

    public ChatSocketSessions(ChatAccounts accounts) { this.accounts = accounts; }
    public void add(WebSocketSession socket) { sockets.put(socket.getId(), socket); }
    public void remove(String id) { sockets.remove(id); }

    public boolean valid(String id) {
        WebSocketSession socket = id == null ? null : sockets.get(id);
        if (socket == null) return false;
        try {
            var auth = ChatSessionHandshake.authenticatedSession(socket.getAttributes());
            accounts.requireEnabled(ChatIdentity.require(auth));
            return socket.isOpen();
        } catch (RuntimeException expired) {
            try { socket.close(new CloseStatus(1008, "SESSION_EXPIRED")); } catch (IOException ignored) {}
            sockets.remove(id);
            return false;
        }
    }

    public void closeExpired() { sockets.keySet().forEach(this::valid); }
}
