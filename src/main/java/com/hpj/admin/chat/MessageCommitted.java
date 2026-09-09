package com.hpj.admin.chat;

/** 重试只重发 ACK，不重复广播或修改会话摘要。 */
public record MessageCommitted(MessageView message, String username, String sessionId, boolean created) {}
