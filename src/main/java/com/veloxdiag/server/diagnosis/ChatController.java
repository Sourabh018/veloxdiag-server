package com.veloxdiag.server.diagnosis;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    public record ChatRequest(String question, String applicationName) {}

    @PostMapping
    public Map<String, String> ask(@RequestBody ChatRequest request) {
        return chatService.answerQuestion(request.question(), request.applicationName());
    }
}