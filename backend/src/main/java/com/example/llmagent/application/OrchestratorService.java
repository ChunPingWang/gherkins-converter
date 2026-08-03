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
import com.example.llmagent.domain.chat.Message;
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
 *
 * <p><b>步驟級重試</b>:ChatService 將 provider 串流失敗降級為 ERROR log + done
 * (實測 ICA 對長產出可能於 10 分鐘級中斷);Orchestrator 偵測該訊號後自動以
 * 新訊息重試該步驟一次(輸入註明前次中斷,請模型重新完整輸出),重試仍失敗
 * 則帶著已收到的部分內容繼續後續步驟,不掛死流程。
 */
@Service
public class OrchestratorService {

    /** 流水線步驟(依序)。以內建 Agent 名稱對應,缺任一即無法啟動。 */
    public static final List<String> STEP_AGENTS = List.of(
            "BDD 規格 Agent", "BRD 業務文件 Agent", "Java 產碼 Agent", "Code Review Agent");

    /** 步驟 2-4 的 prompt 無範本變數;帶預設值僅為 renderPrompt 的防禦。 */
    private static final Map<String, String> DEFAULT_VARS =
            Map.of("gherkin_locale", "zh-TW", "project_name", "llm-webapp");

    private static final String RETRY_PREFIX = "(前次回應串流中斷,請忽略前次輸出,重新完整輸出)\n\n";

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
            String firstInput = c.messages().stream()
                    .filter(m -> m.id().equals(firstMessageId))
                    .map(Message::content).findFirst().orElse("");
            Map<Integer, StringBuilder> outputs = new ConcurrentHashMap<>();
            AtomicBoolean aborted = new AtomicBoolean(false);

            Flux<StreamEvent> s1 = runStepWithRetry(convId, 1, firstInput, firstMessageId, outputs, false);

            Flux<StreamEvent> s2 = Flux.defer(() -> {
                if (aborted.get()) {
                    return Flux.empty();
                }
                String gherkin = extractGherkin(outputs.get(1).toString());
                if (gherkin == null) {
                    return abort(aborted, "步驟 1 未產出 Gherkin 產出物,流程中止");
                }
                return runStepWithRetry(convId, 2,
                        "請依據以下 Gherkin 產出 BRD 套版資料:\n```gherkin\n" + gherkin + "\n```",
                        null, outputs, false);
            });

            Flux<StreamEvent> s3 = Flux.defer(() -> {
                if (aborted.get()) {
                    return Flux.empty();
                }
                String gherkin = extractGherkin(outputs.get(1).toString());
                return runStepWithRetry(convId, 3,
                        "請依據以下 Gherkin 產生完整 Java 21 + Cucumber 程式碼:\n```gherkin\n"
                                + gherkin + "\n```",
                        null, outputs, false);
            });

            Flux<StreamEvent> s4 = Flux.defer(() -> {
                if (aborted.get()) {
                    return Flux.empty();
                }
                return runStepWithRetry(convId, 4,
                        "請審查以下產出的程式碼(正確性、DDD/SOLID、測試涵蓋):\n\n"
                                + outputs.get(3).toString(),
                        null, outputs, true);
            });

            return Flux.concat(s1, s2, s3, s4);
        });
    }

    /**
     * 執行單一步驟,串流中斷(provider ERROR 降級訊號)時以新訊息自動重試一次。
     *
     * @param preCreatedMessageId 步驟 1 由 start() 預先建立的訊息;其餘步驟為 null(此處建立)
     */
    private Flux<StreamEvent> runStepWithRetry(String convId, int idx, String input,
                                               String preCreatedMessageId,
                                               Map<Integer, StringBuilder> outputs, boolean last) {
        AtomicBoolean failed = new AtomicBoolean(false);
        Flux<StreamEvent> first = Flux.defer(() -> {
            String messageId = preCreatedMessageId != null
                    ? preCreatedMessageId : newStepMessage(convId, idx, input);
            return attempt(idx, messageId, outputs, last, failed, true);
        });
        Flux<StreamEvent> retry = Flux.defer(() -> {
            if (!failed.get()) {
                return Flux.empty();
            }
            String agent = STEP_AGENTS.get(idx - 1);
            return Flux.just(
                            StreamEvent.log("WARN", "orchestrator",
                                    "步驟 " + idx + " 串流中斷,自動重試一次:" + agent, ts()),
                            StreamEvent.content("\n\n> 🔁 步驟 " + idx + " 串流中斷,自動重試…\n\n"))
                    .concatWith(Flux.defer(() -> attempt(idx,
                            newStepMessage(convId, idx, RETRY_PREFIX + input),
                            outputs, last, failed, false)));
        });
        return first.concatWith(retry);
    }

    /** 單次嘗試:偵測 provider ERROR 降級訊號標記失敗;首次嘗試失敗時抑制 done 供重試接手。 */
    private Flux<StreamEvent> attempt(int idx, String messageId, Map<Integer, StringBuilder> outputs,
                                      boolean last, AtomicBoolean failed, boolean firstAttempt) {
        StringBuilder buf = outputs.computeIfAbsent(idx, k -> new StringBuilder());
        buf.setLength(0);
        failed.set(false);
        String agent = STEP_AGENTS.get(idx - 1);
        Flux<StreamEvent> header = firstAttempt
                ? Flux.just(
                        StreamEvent.content("\n\n---\n\n## 🧩 步驟 " + idx + "/" + STEP_AGENTS.size()
                                + ":" + agent + "\n\n"),
                        StreamEvent.log("INFO", "orchestrator",
                                "步驟 " + idx + "/" + STEP_AGENTS.size() + " 開始:" + agent, ts()))
                : Flux.empty();
        Flux<StreamEvent> body = chatService.streamAssistant(messageId)
                .doOnNext(ev -> {
                    if (ev.type() == SseEventType.CONTENT) {
                        buf.append(((StreamEvent.ContentDelta) ev.payload()).delta());
                    } else if (ev.type() == SseEventType.LOG
                            && ev.payload() instanceof StreamEvent.LogLine l
                            && "ERROR".equals(l.level()) && "provider".equals(l.source())) {
                        failed.set(true);
                    }
                })
                .concatMap(ev -> {
                    if (ev.type() == SseEventType.DONE) {
                        // 最終步驟且本次未失敗(或已是重試)才放行 done;其餘轉 log
                        boolean passThrough = last && !(failed.get() && firstAttempt);
                        if (passThrough) {
                            return Flux.just(ev);
                        }
                        return Flux.just(StreamEvent.log("INFO", "orchestrator",
                                "步驟 " + idx + (failed.get() ? " 中斷:" : " 完成:") + agent, ts()));
                    }
                    return Flux.just(ev);
                });
        return header.concatWith(body);
    }

    private String newStepMessage(String convId, int idx, String input) {
        AgentProfile p = requireProfile(STEP_AGENTS.get(idx - 1));
        return chatService.addUserMessage(convId, input, null, p.id(), DEFAULT_VARS);
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
