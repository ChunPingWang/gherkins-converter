package com.example.llmagent.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.llmagent.application.port.out.ChatCall;
import com.example.llmagent.application.port.out.ChatModelPort;
import com.example.llmagent.domain.agent.AgentProfile;
import com.example.llmagent.domain.chat.ChatChunk;
import com.example.llmagent.domain.chat.Message;

import reactor.core.publisher.Mono;

/**
 * Agent 自動路由(層次一):以一次輕量 LLM 呼叫判斷訊息意圖,回傳「決策物件」。
 *
 * <p>決策物件的 {@link Target} 預留 {@code PIPELINE} 給層次二(Orchestrator 流程編排),
 * 屆時路由目標多一種型別即可,介面不變。路由呼叫不掛任何工具、不寫入對話,
 * 無法解析或模型不確定時降級為 {@code NONE}(由前端退回人工選擇),不阻斷送出流程。
 */
@Service
public class AgentRouterService {

    /** 路由目標型別。PIPELINE 保留給層次二(流程編排),目前不會產生。 */
    public enum Target { AGENT, PIPELINE, NONE }

    /** 路由決策(層次二沿用同一介面)。 */
    public record RouteDecision(Target target, String agentProfileId, String agentName,
                                double confidence, String reason) {

        public static RouteDecision none(String reason) {
            return new RouteDecision(Target.NONE, null, null, 0.0, reason);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final ChatModelPort chatModelPort;
    private final AgentProfileService profiles;
    private final RuntimeSettingsService settings;

    public AgentRouterService(ChatModelPort chatModelPort, AgentProfileService profiles,
                              RuntimeSettingsService settings) {
        this.chatModelPort = chatModelPort;
        this.profiles = profiles;
        this.settings = settings;
    }

    public Mono<RouteDecision> route(String message) {
        if (message == null || message.isBlank()) {
            return Mono.just(RouteDecision.none("訊息為空"));
        }
        List<AgentProfile> candidates = profiles.listLatest().stream()
                .filter(AgentProfile::enabled)
                .toList();
        if (candidates.isEmpty()) {
            return Mono.just(RouteDecision.none("無可用 Agent"));
        }
        ChatCall call = new ChatCall(
                settings.defaultModelId(), null, routingPrompt(candidates),
                List.of(Message.user(UUID.randomUUID().toString(), message, Instant.now())),
                List.of());
        return chatModelPort.stream(call)
                .map(ChatChunk::textDelta)
                .filter(Objects::nonNull)
                .collect(Collectors.joining())
                .map(reply -> parse(reply, candidates))
                .onErrorResume(e -> Mono.just(RouteDecision.none("路由呼叫失敗:" + e.getMessage())));
    }

    private String routingPrompt(List<AgentProfile> candidates) {
        String list = candidates.stream()
                .map(p -> "- id: " + p.id() + " | 名稱: " + p.name() + " | 說明: "
                        + (p.description() == null ? "" : p.description()))
                .collect(Collectors.joining("\n"));
        return """
                你是 Agent 路由器。依使用者訊息,從下列候選 Agent 選出最適合處理者。
                只輸出一個 JSON,不要 code fence、不要任何其他文字:
                {"agentProfileId":"<候選 id>","confidence":<0.0~1.0>,"reason":"<簡短繁中理由>"}
                判斷依據是「這則訊息要求的產物」;無法明確判斷時輸出
                {"agentProfileId":"none","confidence":0,"reason":"<原因>"}。
                候選 Agent:
                """ + list;
    }

    /** 解析模型回覆;任何偏差(非 JSON、未知 id)一律降級 NONE,不拋出。 */
    private RouteDecision parse(String reply, List<AgentProfile> candidates) {
        String cleaned = reply.replaceAll("(?s)<think>.*?</think>", "");
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return RouteDecision.none("模型回覆非 JSON");
        }
        try {
            Map<?, ?> json = MAPPER.readValue(cleaned.substring(start, end + 1), Map.class);
            String id = String.valueOf(json.get("agentProfileId"));
            String reason = json.get("reason") == null ? "" : String.valueOf(json.get("reason"));
            if ("none".equalsIgnoreCase(id)) {
                return RouteDecision.none(reason.isBlank() ? "模型無法判斷" : reason);
            }
            AgentProfile matched = candidates.stream()
                    .filter(p -> p.id().equals(id) || p.name().equals(id))
                    .findFirst().orElse(null);
            if (matched == null) {
                return RouteDecision.none("模型回傳未知 Agent:" + id);
            }
            double confidence = json.get("confidence") instanceof Number n
                    ? Math.clamp(n.doubleValue(), 0.0, 1.0) : 0.0;
            return new RouteDecision(Target.AGENT, matched.id(), matched.name(), confidence, reason);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return RouteDecision.none("模型回覆 JSON 解析失敗");
        }
    }
}
