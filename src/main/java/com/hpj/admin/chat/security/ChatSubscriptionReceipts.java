package com.hpj.admin.chat.security;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.stereotype.Component;

/** 简单 broker 不实现 STOMP receipt；订阅真正注册后确认，客户端再补拉历史以消除订阅空窗。 */
@Component
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatSubscriptionReceipts implements SmartInitializingSingleton {
    private final ExecutorSubscribableChannel inbound;
    private final MessageChannel outbound;
    private final ExecutorSubscribableChannel broker;

    public ChatSubscriptionReceipts(@Qualifier("clientInboundChannel") ExecutorSubscribableChannel inbound,
                                    @Qualifier("brokerChannel") ExecutorSubscribableChannel broker,
                                    @Qualifier("clientOutboundChannel") MessageChannel outbound) {
        this.inbound = inbound;
        this.broker = broker;
        this.outbound = outbound;
    }

    @Override public void afterSingletonsInstantiated() {
        ExecutorChannelInterceptor receipts = new ExecutorChannelInterceptor() {
            @Override public void afterMessageHandled(Message<?> message, MessageChannel channel,
                                                      MessageHandler handler, Exception error) {
                if (error != null || !(handler instanceof SimpleBrokerMessageHandler)) return;
                SimpMessageHeaderAccessor request = SimpMessageHeaderAccessor.wrap(message);
                String destination = request.getDestination();
                String receiptId = request.getFirstNativeHeader("receipt");
                // /user 队列须先经用户目的地解析器改写，再在 brokerChannel 注册。
                // 不能把简单 broker 忽略原始 /user 帧误当成订阅成功。
                if (request.getMessageType() != SimpMessageType.SUBSCRIBE || receiptId == null || destination == null
                        || !(destination.startsWith("/topic/") || destination.startsWith("/queue/"))) return;
                StompHeaderAccessor receipt = StompHeaderAccessor.create(StompCommand.RECEIPT);
                receipt.setSessionId(request.getSessionId());
                receipt.setReceiptId(receiptId);
                outbound.send(MessageBuilder.createMessage(new byte[0], receipt.getMessageHeaders()));
            }
        };
        inbound.addInterceptor(receipts);
        broker.addInterceptor(receipts);
    }
}
