package com.example.llmagent.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * 層次二 Orchestrator:SDLC 流水線(部分對齊 GitHub Spec-Kit:Specify → Plan →
 * Implement → Analyze,見 README 原理 11)。五個 Agent 於**同一對話**內依序接力:
 *
 * <ol>
 *   <li>Specify:BDD 規格 Agent(Gherkin,含 Clarify 行為)→ BRD 業務文件 Agent</li>
 *   <li>Plan:DDD 設計 Agent(Bounded Context/聚合根/Command/Event + CONTEXTS 標記)</li>
 *   <li>Implement:Java 產碼 Agent —— 依 Plan 的 CONTEXTS **逐批產碼**
 *       (每個 bounded context 一批,天然切短單次串流,緩解 ICA 長串流中斷;無標記時單批)</li>
 *   <li>Analyze:Code Review Agent(品質審查 + Gherkin↔實作↔測試追溯檢查)</li>
 * </ol>
 *
 * <p>沿用 WP3-T3 逐訊息切換 Agent,追溯鏈與產出物版本化不變。全流程單一 SSE 串流:
 * 步驟邊界以 content 標題與 orchestrator log 呈現(log 格式「步驟 i/n 開始」為
 * 前端階段 Tab 的切換訊號);中間步驟 done 轉 log,僅最終步驟發 done。
 * 串流中斷(provider ERROR 降級訊號)自動以新訊息重試該步驟/批次一次。
 */
@Service
public class OrchestratorService {

    /** 流水線步驟(依序)。以內建 Agent 名稱對應,缺任一即無法啟動。 */
    public static final List<String> STEP_AGENTS = List.of(
            "BDD 規格 Agent", "BRD 業務文件 Agent", "DDD 設計 Agent",
            "Java 產碼 Agent", "Code Review Agent");

    /** Plan 產出的機器可讀 bounded context 清單標記。 */
    private static final Pattern CONTEXTS_LINE = Pattern.compile("(?m)^CONTEXTS:\\s*(.+)\\s*$");

    /** 產碼批次上限:防 Plan 過度切分造成批次爆量。 */
    private static final int MAX_BATCHES = 6;

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
            AtomicBoolean aborted = new AtomicBoolean(false);
            StringBuilder specBuf = new StringBuilder();
            StringBuilder planBuf = new StringBuilder();
            StringBuilder javaAll = new StringBuilder();

            Flux<StreamEvent> s1 = runStepWithRetry(convId, 1, null, firstMessageId, specBuf, null, false);

            Flux<StreamEvent> s2 = Flux.defer(() -> {
                if (aborted.get()) {
                    return Flux.empty();
                }
                String gherkin = extractGherkin(specBuf.toString());
                if (gherkin == null) {
                    return abort(aborted, "步驟 1 未產出 Gherkin(可能在 Clarify:要求釐清需求)。"
                            + "流程中止;請依回覆補充資訊後重新執行全流程");
                }
                return runStepWithRetry(convId, 2,
                        "請依據以下 Gherkin 產出 BRD 套版資料:\n```gherkin\n" + gherkin + "\n```",
                        null, new StringBuilder(), null, false);
            });

            Flux<StreamEvent> s3 = Flux.defer(() -> {
                if (aborted.get()) {
                    return Flux.empty();
                }
                return runStepWithRetry(convId, 3,
                        "請依據以下 Gherkin 產出 DDD 技術計畫(Plan):\n```gherkin\n"
                                + extractGherkin(specBuf.toString()) + "\n```",
                        null, planBuf, null, false);
            });

            Flux<StreamEvent> s4 = Flux.defer(() -> {
                if (aborted.get()) {
                    return Flux.empty();
                }
                String gherkin = extractGherkin(specBuf.toString());
                String plan = planBuf.toString();
                List<String> contexts = parseContexts(plan);
                if (contexts.isEmpty()) {
                    return implementBatch(convId, gherkin, plan, null, 0, 0, javaAll);
                }
                List<Flux<StreamEvent>> batches = new ArrayList<>();
                for (int i = 0; i < contexts.size(); i++) {
                    final int idx = i;
                    batches.add(Flux.defer(() -> implementBatch(
                            convId, gherkin, plan, contexts.get(idx), idx + 1, contexts.size(), javaAll)));
                }
                return Flux.concat(batches);
            });

            Flux<StreamEvent> s5 = Flux.defer(() -> {
                if (aborted.get()) {
                    return Flux.empty();
                }
                return runStepWithRetry(convId, 5,
                        "請審查以下產出的程式碼(正確性、DDD/SOLID、測試涵蓋),並依 Gherkin 做追溯一致性分析:\n\n"
                                + "【Gherkin 規格】\n```gherkin\n" + extractGherkin(specBuf.toString())
                                + "\n```\n\n【程式碼】\n" + javaAll,
                        null, new StringBuilder(), null, true);
            });

            return Flux.concat(s1, s2, s3, s4, s5);
        });
    }

    /** Implement 單一批次:限定 bounded context 產碼(context 為 null 表示不分批)。 */
    private Flux<StreamEvent> implementBatch(String convId, String gherkin, String plan,
                                             String context, int batchNo, int batchTotal,
                                             StringBuilder javaAll) {
        String scope = context == null
                ? "請產出完整程式碼。"
                : "本批次**只**產出 bounded context「" + context + "」的程式碼(其他 context 由後續批次處理)。";
        String input = "請依據以下 Gherkin 與 DDD 技術計畫產生 Java 21 + Cucumber 程式碼。" + scope
                + "\n\n【Gherkin 規格】\n```gherkin\n" + gherkin + "\n```\n\n【DDD 技術計畫】\n" + plan;
        String batchLabel = context == null ? null : "批次 " + batchNo + "/" + batchTotal + ":" + context;
        StringBuilder batchBuf = new StringBuilder();
        return runStepWithRetry(convId, 4, input, null, batchBuf, batchLabel, false)
                .concatWith(Flux.defer(() -> {
                    javaAll.append(batchBuf).append('\n');
                    return Flux.empty();
                }));
    }

    /**
     * 執行單一步驟/批次,串流中斷(provider ERROR 降級訊號)時以新訊息自動重試一次。
     *
     * @param preCreatedMessageId 步驟 1 由 start() 預先建立的訊息;其餘為 null(此處建立)
     * @param batchLabel          Implement 分批時的批次標籤(僅影響顯示)
     */
    private Flux<StreamEvent> runStepWithRetry(String convId, int idx, String input,
                                               String preCreatedMessageId, StringBuilder buf,
                                               String batchLabel, boolean last) {
        AtomicBoolean failed = new AtomicBoolean(false);
        Flux<StreamEvent> first = Flux.defer(() -> {
            String messageId = preCreatedMessageId != null
                    ? preCreatedMessageId : newStepMessage(convId, idx, input);
            return attempt(idx, messageId, buf, batchLabel, last, failed, true);
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
                            buf, batchLabel, last, failed, false)));
        });
        return first.concatWith(retry);
    }

    /** 單次嘗試:偵測 provider ERROR 降級訊號標記失敗;首次嘗試失敗時抑制 done 供重試接手。 */
    private Flux<StreamEvent> attempt(int idx, String messageId, StringBuilder buf, String batchLabel,
                                      boolean last, AtomicBoolean failed, boolean firstAttempt) {
        buf.setLength(0);
        failed.set(false);
        String agent = STEP_AGENTS.get(idx - 1);
        String title = "## 🧩 步驟 " + idx + "/" + STEP_AGENTS.size() + ":" + agent
                + (batchLabel == null ? "" : "(" + batchLabel + ")");
        String startLog = "步驟 " + idx + "/" + STEP_AGENTS.size() + " 開始:" + agent
                + (batchLabel == null ? "" : "(" + batchLabel + ")");
        // log 先於 content 標題:前端以「步驟 i/n 開始」log 作為階段 Tab 切換訊號,
        // 標題 content 必須落在切換後的階段桶內
        Flux<StreamEvent> header = firstAttempt
                ? Flux.just(
                        StreamEvent.log("INFO", "orchestrator", startLog, ts()),
                        StreamEvent.content("\n\n---\n\n" + title + "\n\n"))
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
                        boolean passThrough = last && !(failed.get() && firstAttempt);
                        if (passThrough) {
                            return Flux.just(ev);
                        }
                        return Flux.just(StreamEvent.log("INFO", "orchestrator",
                                "步驟 " + idx + (failed.get() ? " 中斷:" : " 完成:") + agent
                                        + (batchLabel == null ? "" : "(" + batchLabel + ")"), ts()));
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

    /** 解析 Plan 的 CONTEXTS 標記;無標記或超量截斷(截斷時保留前 MAX_BATCHES 個)。 */
    static List<String> parseContexts(String plan) {
        Matcher m = CONTEXTS_LINE.matcher(plan == null ? "" : plan);
        String line = null;
        while (m.find()) {
            line = m.group(1); // 取最後一次出現(重試後以最新為準)
        }
        if (line == null) {
            return List.of();
        }
        return java.util.Arrays.stream(line.split("[,、]"))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .limit(MAX_BATCHES)
                .toList();
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
