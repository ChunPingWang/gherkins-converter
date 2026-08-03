package com.example.llmagent.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import com.example.llmagent.application.event.StreamEvent;
import com.example.llmagent.application.port.out.ConversationStore;
import com.example.llmagent.domain.agent.AgentProfile;
import com.example.llmagent.domain.artifact.Artifact;
import com.example.llmagent.domain.artifact.ArtifactExtractor;
import com.example.llmagent.domain.chat.Conversation;
import com.example.llmagent.domain.sse.SseEventType;

import reactor.core.publisher.Flux;

/**
 * 層次二 Orchestrator:以固定 SDLC 流水線協調多個 Agent 依序接力
 * (BDD 規格 → BRD 業務文件 → Java 產碼 → Code Review),於**同一對話**內
 * 逐步建立訊息(沿用 WP3-T3 對話中切換 Agent,追溯鏈不變),前一步驟的產出
 * (Gherkin/程式碼)自動餵給下一步驟。
 *
 * <p>全流程串流為單一 SSE 連線:步驟邊界以 content 標題與 orchestrator log 呈現;
 * 中間步驟的 done 事件轉為 log(僅最終步驟發出 done,前端據以關閉連線)。
 * 步驟 1 未產出 Gherkin 時優雅中止(ERROR log + done),不留半掛串流。
 */
@Service
public class OrchestratorService {

    /** 流水線步驟(依序)。以內建 Agent 名稱對應,缺任一即無法啟動。 */
    public static final List<String> STEP_AGENTS = List.of(
            "BDD 規格 Agent", "BRD 業務文件 Agent", "Java 產碼 Agent", "Code Review Agent");

    /** 步驟 2-4 的 prompt 無範本變數;帶預設值僅為 renderPrompt 的防禦。 */
    private static final Map<String, String> DEFAULT_VARS =
            Map.of("gherkin_locale", "zh-TW", "project_name", "llm-webapp");

    private final ChatService chatService;
    private final AgentProfileService profiles;
    private final ConversationStore store;

    public OrchestratorService(ChatService chatService, AgentProfileService profiles,
                               ConversationStore store) {
        this.chatService = chatService;
        this.profiles = profiles;
        this.store = store;
    }

    public record StartResult(String messageId, List<String> steps) {
    }

    /** 啟動流水線:建立步驟 1(BDD)的 user 訊息;串流由 stream() 接手。 */
    public StartResult start(String conversationId, String content, Map<String, String> promptVariables) {
        for (String name : STEP_AGENTS) {
            requireProfile(name);
        }
        Map<String, String> vars = promptVariables == null || promptVariables.isEmpty()
                ? DEFAULT_VARS : promptVariables;
        AgentProfile first = requireProfile(STEP_AGENTS.get(0));
        String messageId = chatService.addUserMessage(conversationId, content, null, first.id(), vars);
        return new StartResult(messageId, STEP_AGENTS);
    }

    /** 串流整條流水線(firstMessageId 為 start() 建立的步驟 1 訊息)。 */
    public Flux<StreamEvent> stream(String firstMessageId) {
        return Flux.defer(() -> {
            Conversation c = store.findByMessageId(firstMessageId)
                    .orElseThrow(() -> new IllegalArgumentException("message not found: " + firstMessageId));
            String convId = c.id();
            Map<Integer, StringBuilder> outputs = new ConcurrentHashMap<>();
            AtomicBoolean aborted = new AtomicBoolean(false);

            Flux<StreamEvent> s1 = runStep(1, firstMessageId, outputs, false);

            Flux<StreamEvent> s2 = Flux.defer(() -> {
                if (aborted.get()) {
                    return Flux.empty();
                }
                String gherkin = extractGherkin(outputs.get(1).toString());
                if (gherkin == null) {
                    return abort(aborted, "步驟 1 未產出 Gherkin 產出物,流程中止");
                }
                return nextStep(convId, 2,
                        "請依據以下 Gherkin 產出 BRD 套版資料:\n```gherkin\n" + gherkin + "\n```",
                        outputs, false);
            });

            Flux<StreamEvent> s3 = Flux.defer(() -> {
                if (aborted.get()) {
                    return Flux.empty();
                }
                String gherkin = extractGherkin(outputs.get(1).toString());
                return nextStep(convId, 3,
                        "請依據以下 Gherkin 產生完整 Java 21 + Cucumber 程式碼:\n```gherkin\n"
                                + gherkin + "\n```",
                        outputs, false);
            });

            Flux<StreamEvent> s4 = Flux.defer(() -> {
                if (aborted.get()) {
                    return Flux.empty();
                }
                return nextStep(convId, 4,
                        "請審查以下產出的程式碼(正確性、DDD/SOLID、測試涵蓋):\n\n"
                                + outputs.get(3).toString(),
                        outputs, true);
            });

            return Flux.concat(s1, s2, s3, s4);
        });
    }

    private Flux<StreamEvent> runStep(int idx, String messageId,
                                      Map<Integer, StringBuilder> outputs, boolean last) {
        StringBuilder buf = outputs.computeIfAbsent(idx, k -> new StringBuilder());
        String agent = STEP_AGENTS.get(idx - 1);
        Flux<StreamEvent> header = Flux.just(
                StreamEvent.content("\n\n---\n\n## 🧩 步驟 " + idx + "/" + STEP_AGENTS.size()
                        + ":" + agent + "\n\n"),
                StreamEvent.log("INFO", "orchestrator",
                        "步驟 " + idx + "/" + STEP_AGENTS.size() + " 開始:" + agent, ts()));
        Flux<StreamEvent> body = chatService.streamAssistant(messageId)
                .doOnNext(ev -> {
                    if (ev.type() == SseEventType.CONTENT) {
                        buf.append(((StreamEvent.ContentDelta) ev.payload()).delta());
                    }
                })
                .concatMap(ev -> {
                    if (ev.type() == SseEventType.DONE && !last) {
                        // 中間步驟的 done 轉 log,避免前端提早關閉 SSE;僅最終步驟發 done
                        return Flux.just(StreamEvent.log("INFO", "orchestrator",
                                "步驟 " + idx + " 完成:" + agent, ts()));
                    }
                    return Flux.just(ev);
                });
        return header.concatWith(body);
    }

    private Flux<StreamEvent> nextStep(String convId, int idx, String input,
                                       Map<Integer, StringBuilder> outputs, boolean last) {
        AgentProfile p = requireProfile(STEP_AGENTS.get(idx - 1));
        String messageId = chatService.addUserMessage(convId, input, null, p.id(), DEFAULT_VARS);
        return runStep(idx, messageId, outputs, last);
    }

    private Flux<StreamEvent> abort(AtomicBoolean aborted, String reason) {
        aborted.set(true);
        return Flux.just(
                StreamEvent.log("ERROR", "orchestrator", reason, ts()),
                StreamEvent.done(new StreamEvent.DoneInfo(new StreamEvent.UsageInfo(0, 0), 0, 0)));
    }

    private String extractGherkin(String content) {
        return ArtifactExtractor.extract(content).pieces().stream()
                .filter(p -> p.type() == Artifact.ArtifactType.GHERKIN)
                .map(ArtifactExtractor.Extraction.Piece::content)
                .findFirst().orElse(null);
    }

    private AgentProfile requireProfile(String name) {
        return profiles.listLatest().stream()
                .filter(p -> name.equals(p.name()) && p.enabled())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("流水線所需 Agent 不存在:" + name));
    }

    private static String ts() {
        return Instant.now().toString();
    }
}
