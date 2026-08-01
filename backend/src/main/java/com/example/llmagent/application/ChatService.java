package com.example.llmagent.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.example.llmagent.application.event.StreamEvent;
import com.example.llmagent.application.event.StreamEvent.DoneInfo;
import com.example.llmagent.application.event.StreamEvent.UsageInfo;
import com.example.llmagent.application.port.out.ChatCall;
import com.example.llmagent.application.port.out.ChatModelPort;
import com.example.llmagent.application.port.out.ConversationStore;
import com.example.llmagent.domain.chat.ChatChunk;
import com.example.llmagent.domain.chat.Conversation;
import com.example.llmagent.domain.chat.Message;
import com.example.llmagent.domain.chat.Usage;
import com.example.llmagent.domain.thinking.Segment;
import com.example.llmagent.domain.thinking.ThinkingParser;

import reactor.core.publisher.Flux;

/**
 * 對話應用服務:建立對話、記錄使用者訊息、串流模型回應。
 *
 * <p>串流時將 Provider 片段經 {@link ThinkingParser} 分流為 thinking / content 事件,
 * 並於首個 token 記錄 TTFT、結束時記錄 token 用量與耗時(規劃書 §5.1、ADR-003)。
 */
@Service
public class ChatService {

    private final ChatModelPort chatModelPort;
    private final ConversationStore store;
    private final RuntimeSettingsService settings;
    private final AgentProfileService agentProfiles;
    private final ArtifactService artifacts;
    private final com.example.llmagent.application.port.out.AuditLogStore auditLog;
    private final io.micrometer.observation.ObservationRegistry observations;

    public ChatService(ChatModelPort chatModelPort,
                       ConversationStore store,
                       RuntimeSettingsService settings,
                       AgentProfileService agentProfiles,
                       ArtifactService artifacts,
                       com.example.llmagent.application.port.out.AuditLogStore auditLog,
                       io.micrometer.observation.ObservationRegistry observations) {
        this.chatModelPort = chatModelPort;
        this.store = store;
        this.settings = settings;
        this.agentProfiles = agentProfiles;
        this.artifacts = artifacts;
        this.auditLog = auditLog;
        this.observations = observations;
    }

    /**
     * 建立對話。優先序:明示 systemPrompt > Agent Profile(套範本變數)> 全域預設。
     * 指定 agentProfileId 時,模型預設亦取自該 Profile(ADR-006)。
     */
    public Conversation createConversation(String title, String modelId, String systemPrompt,
                                           Double temperature, String agentProfileId,
                                           java.util.Map<String, String> promptVariables) {
        String prompt = systemPrompt;
        String model = modelId;
        if ((prompt == null || prompt.isBlank()) && agentProfileId != null && !agentProfileId.isBlank()) {
            prompt = agentProfiles.renderPrompt(agentProfileId, promptVariables);
            if (model == null || model.isBlank()) {
                model = agentProfiles.findLatest(agentProfileId)
                        .map(p -> p.defaultModelId()).orElse(null);
            }
        }
        Conversation c = new Conversation(
                UUID.randomUUID().toString(),
                title == null || title.isBlank() ? "新對話" : title,
                prompt == null || prompt.isBlank() ? settings.systemPrompt() : prompt,
                model == null || model.isBlank() ? settings.defaultModelId() : model,
                temperature,
                Instant.now());
        store.save(c);
        return c;
    }

    /**
     * 清除對話與其全部訊息、產出物(重新開始)。冪等;
     * 稽核日誌保留並落一筆刪除紀錄(audit_logs 無 FK,不受 cascade 影響)。
     */
    public void deleteConversation(String conversationId) {
        artifacts.deleteByConversation(conversationId);
        store.deleteById(conversationId);
        auditLog.append(conversationId, "INFO", "conversation", "conversation_deleted");
    }

    /** 記錄使用者訊息,回傳 messageId(供 /stream 消費)。 */
    public String addUserMessage(String conversationId, String content) {
        return addUserMessage(conversationId, content, null, null, null);
    }

    /**
     * 記錄使用者訊息,可於對話中切換模型 / Agent Profile(WP3-T3):
     * 覆寫自本則訊息起生效,並記錄於訊息(model_id / agent_profile_id+version)供追溯。
     */
    public String addUserMessage(String conversationId, String content,
                                 String modelId, String agentProfileId,
                                 java.util.Map<String, String> promptVariables) {
        Conversation c = store.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("conversation not found: " + conversationId));
        Integer profileVersion = null;
        if (modelId != null && !modelId.isBlank()) {
            c.switchModel(modelId);
        }
        if (agentProfileId != null && !agentProfileId.isBlank()) {
            c.switchSystemPrompt(agentProfiles.renderPrompt(agentProfileId, promptVariables));
            profileVersion = agentProfiles.findLatest(agentProfileId)
                    .map(p -> p.version()).orElse(null);
        }
        Message m = Message.user(UUID.randomUUID().toString(), content,
                modelId == null || modelId.isBlank() ? null : modelId,
                agentProfileId == null || agentProfileId.isBlank() ? null : agentProfileId,
                profileVersion, Instant.now());
        c.addMessage(m);
        store.save(c);
        return m.id();
    }

    /**
     * 串流指定 user 訊息的 assistant 回應,產生五型事件序列。
     * 完成後將 assistant 訊息寫回對話。
     */
    public Flux<StreamEvent> streamAssistant(String userMessageId) {
        return Flux.defer(() -> {
            Conversation c = store.findByMessageId(userMessageId)
                    .orElseThrow(() -> new IllegalArgumentException("message not found: " + userMessageId));
            String model = c.defaultModelId();
            // 工具授權(ADR-003 tool_call):取對話中最近一次指定的 Agent Profile 之 tools 欄位
            List<String> tools = c.messages().stream()
                    .filter(m -> m.agentProfileId() != null)
                    .reduce((a, b) -> b)
                    .flatMap(m -> agentProfiles.findLatest(m.agentProfileId()))
                    .map(p -> p.tools())
                    .orElse(List.of());
            ChatCall call = new ChatCall(
                    model, c.temperature(), c.systemPrompt(),
                    c.historyUpToAndIncluding(userMessageId), tools);

            long start = System.currentTimeMillis();
            AtomicLong ttft = new AtomicLong(-1);
            AtomicReference<Usage> usageRef = new AtomicReference<>(Usage.ZERO);
            ThinkingParser parser = new ThinkingParser();
            StringBuilder assistant = new StringBuilder();

            Flux<StreamEvent> startLog = Flux.just(
                    StreamEvent.log("INFO", "provider", "model=" + model + " 串流開始", ts()));

            Flux<StreamEvent> body = chatModelPort.stream(call).concatMap(chunk -> {
                List<StreamEvent> evs = new ArrayList<>();
                if (chunk.usage() != null) {
                    usageRef.set(chunk.usage());
                }
                if (chunk.toolCall() != null) {
                    var tc = chunk.toolCall();
                    evs.add(StreamEvent.toolCall(tc.name(), parseToolArguments(tc.argumentsJson()), tc.status()));
                    evs.add(StreamEvent.log("INFO", "tool", "工具 " + tc.name() + " " + tc.status(), ts()));
                }
                String delta = chunk.textDelta();
                if (delta != null && !delta.isEmpty()) {
                    if (ttft.compareAndSet(-1, System.currentTimeMillis() - start)) {
                        evs.add(StreamEvent.log("INFO", "provider", "TTFT=" + ttft.get() + "ms", ts()));
                    }
                    for (Segment s : parser.accept(delta)) {
                        appendSegment(evs, assistant, s);
                    }
                }
                return Flux.fromIterable(evs);
            });

            Flux<StreamEvent> tail = Flux.defer(() -> {
                List<StreamEvent> evs = new ArrayList<>();
                for (Segment s : parser.flush()) {
                    appendSegment(evs, assistant, s);
                }
                String assistantMsgId = UUID.randomUUID().toString();
                c.addMessage(Message.assistant(assistantMsgId, model, assistant.toString(), Instant.now()));
                store.save(c);

                // 產出物抽取(WP5-T1):訊息落庫後執行,版本化入庫;降級記 WARN
                ArtifactService.ExtractResult extracted =
                        artifacts.extractAndStore(c.id(), assistantMsgId, assistant.toString());
                if (extracted.degraded()) {
                    evs.add(StreamEvent.log("WARN", "artifact",
                            "code fence 格式偏差,整段降級為 MARKDOWN 產出物", ts()));
                }
                for (var a : extracted.artifacts()) {
                    evs.add(StreamEvent.log("INFO", "artifact",
                            "產出物 " + a.type() + " v" + a.version() + " (" + a.id().substring(0, 8) + ")", ts()));
                }

                Usage u = usageRef.get();
                long elapsed = System.currentTimeMillis() - start;
                evs.add(StreamEvent.log("INFO", "provider",
                        "完成 tokens=" + u.promptTokens() + "/" + u.completionTokens()
                                + " elapsed=" + elapsed + "ms", ts()));
                evs.add(StreamEvent.done(new DoneInfo(
                        new UsageInfo(u.promptTokens(), u.completionTokens()),
                        elapsed, Math.max(ttft.get(), 0))));
                return Flux.fromIterable(evs);
            });

            // 追蹤 span(WP4-T4,ADR-007):attributes 含 conversation_id / agent_profile_version
            Integer profileVersion = c.messages().stream()
                    .filter(m -> m.agentProfileVersion() != null)
                    .reduce((a, b) -> b).map(Message::agentProfileVersion).orElse(null);
            io.micrometer.observation.Observation obs =
                    io.micrometer.observation.Observation.createNotStarted("chat.stream", observations)
                            .lowCardinalityKeyValue("model", model)
                            .highCardinalityKeyValue("conversation_id", c.id())
                            .highCardinalityKeyValue("agent_profile_version",
                                    profileVersion == null ? "none" : String.valueOf(profileVersion));

            return Flux.concat(startLog, body, tail)
                    .onErrorResume(err -> Flux.just(
                            StreamEvent.log("ERROR", "provider",
                                    "串流失敗:" + err.getMessage(), ts()),
                            StreamEvent.done(new DoneInfo(new UsageInfo(0, 0),
                                    System.currentTimeMillis() - start, Math.max(ttft.get(), 0)))))
                    // 稽核落地(WP4-T3):log/done 事件同步寫入 audit_logs
                    .doOnNext(ev -> audit(c.id(), ev))
                    .doOnSubscribe(s -> obs.start())
                    .doFinally(sig -> obs.stop());
        });
    }

    private void audit(String conversationId, StreamEvent ev) {
        try {
            switch (ev.type()) {
                case LOG -> {
                    StreamEvent.LogLine l = (StreamEvent.LogLine) ev.payload();
                    auditLog.append(conversationId, l.level(), l.source(), l.msg());
                }
                case DONE -> auditLog.append(conversationId, "INFO", "done", String.valueOf(ev.payload()));
                default -> { /* thinking/content 不落稽核,量大且屬內容而非事件 */ }
            }
        } catch (Exception ignore) {
            // 稽核失敗不阻斷對話串流
        }
    }

    private void appendSegment(List<StreamEvent> evs, StringBuilder assistant, Segment s) {
        switch (s.kind()) {
            case THINKING -> evs.add(StreamEvent.thinking(s.text()));
            case CONTENT -> {
                assistant.append(s.text());
                evs.add(StreamEvent.content(s.text()));
            }
            default -> { /* THINKING/CONTENT only from parser */ }
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper ARGS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 工具參數 JSON 轉物件(openapi tool_call.arguments: object);解析失敗降級為原字串。 */
    private static Object parseToolArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return ARGS_MAPPER.readValue(argumentsJson, java.util.Map.class);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return argumentsJson;
        }
    }

    private static String ts() {
        return Instant.now().toString();
    }
}
