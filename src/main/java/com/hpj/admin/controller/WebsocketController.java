package com.hpj.admin.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class WebsocketController {

    @Autowired
    private SimpMessagingTemplate template;

    @MessageMapping("/aaa")
    public void handle(String message) {
        System.out.println(message);
        template.convertAndSend("/topic/greetings", message);
    }

    @MessageMapping("/bbb")
    public void handler(String message) {
        System.out.println(message);
    }
}
