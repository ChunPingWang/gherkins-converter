package com.example.llmagent.adapter.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.llmagent.application.OrchestratorService;
import com.example.llmagent.application.event.StreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import reactor.core.publisher.Flux;

/**
 * 層次二 Orchestrator 端點:啟動 SDLC 流水線並以單一 SSE 串流全程。
 * 沿用「POST 建訊息 → GET 串流」的既有模式(EventSource 僅支援 GET);
 * 契約見 specs/openapi.yaml。
 */
@RestController
@RequestMapping("/api")
public class OrchestratorController {

    private final OrchestratorService orchestrator;
    private final ObjectMapper objectMapper;

    public OrchestratorController(OrchestratorService orchestrator, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    public record OrchestrateRequest(@NotBlank String content, Map<String, String> promptVariables) {
    }

    public record OrchestrateResponse(String messageId, List<String> steps) {
    }

    @PostMapping("/conversations/{conversationId}/orchestrate")
    @ResponseStatus(HttpStatus.CREATED)
    public OrchestrateResponse start(@PathVariable String conversationId,
                                     @Valid @RequestBody OrchestrateRequest req) {
        OrchestratorService.StartResult r =
                orchestrator.start(conversationId, req.content(), req.promptVariables());
        return new OrchestrateResponse(r.messageId(), r.steps());
    }

    @GetMapping(path = "/messages/{messageId}/orchestrate/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String messageId) {
        return orchestrator.stream(messageId).map(this::toSse);
    }

    private ServerSentEvent<String> toSse(StreamEvent event) {
        return ServerSentEvent.<String>builder()
                .event(event.type().wireName())
                .data(writeJson(event.payload()))
                .build();
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization\"}";
        }
    }
}
