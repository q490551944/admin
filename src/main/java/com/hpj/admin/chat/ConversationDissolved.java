package com.hpj.admin.chat;

/** 房间解散的事务内事件，由监听器在提交后转换成 WebSocket 通知。 */
public record ConversationDissolved(long conversationId) {}
