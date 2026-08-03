package com.example.llmagent.acceptance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.example.llmagent.adapter.out.persistence.InMemoryAgentProfileStore;
import com.example.llmagent.adapter.out.persistence.InMemoryArtifactStore;
import com.example.llmagent.adapter.out.persistence.InMemoryAuditLogStore;
import com.example.llmagent.adapter.out.persistence.InMemoryConversationStore;
import com.example.llmagent.application.AgentProfileService;
import com.example.llmagent.application.ArtifactService;
import com.example.llmagent.application.ChatProperties;
import com.example.llmagent.application.ChatService;
import com.example.llmagent.application.OrchestratorService;
import com.example.llmagent.application.RuntimeSettingsService;
import com.example.llmagent.application.event.StreamEvent;
import com.example.llmagent.application.port.out.ChatCall;
import com.example.llmagent.domain.artifact.Artifact;
import com.example.llmagent.domain.chat.ChatChunk;
import com.example.llmagent.domain.chat.Role;
import com.example.llmagent.domain.chat.Usage;
import com.example.llmagent.domain.sse.SseEventType;

import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 層次二 Orchestrator steps。fake provider 依步驟序回放,離線決定性執行。 */
public class OrchestratorSteps {

    private static final String GHERKIN_REPLY =
            "```gherkin\n# language: zh-TW\n功能: 使用者登入\n場景: 成功登入\n```";
    private static final String JAVA_REPLY =
            "```java\n// src/main/java/App.java\npublic class App {}\n```";

    /** 腳本項:text 為回覆內容;fail=true 模擬串流中途中斷(部分內容後 error)。 */
    private record Scripted(String text, boolean fail) {
    }

    private AgentProfileService profileService;
    private ChatService chatService;
    private ArtifactService artifactService;
    private InMemoryConversationStore conversationStore;
    private OrchestratorService orchestrator;
    private final Deque<Scripted> replies = new ArrayDeque<>();
    private final List<ChatCall> calls = new ArrayList<>();
    private String conversationId;
    private List<StreamEvent> events;

    @Before
    public void setup() {
        replies.clear();
        calls.clear();
        profileService = new AgentProfileService(new InMemoryAgentProfileStore(), null);
        conversationStore = new InMemoryConversationStore();
        artifactService = new ArtifactService(new InMemoryArtifactStore());
        chatService = new ChatService(
                call -> {
                    calls.add(call);
                    Scripted s = replies.isEmpty() ? new Scripted("", false) : replies.poll();
                    Flux<ChatChunk> head = Flux.just(ChatChunk.text(s.text()));
                    return s.fail()
                            ? head.concatWith(Flux.error(new RuntimeException("stream cut")))
                            : head.concatWith(Flux.just(ChatChunk.finalUsage(new Usage(10, 5))));
                },
                conversationStore,
                new RuntimeSettingsService(new ChatProperties("test-model", "sys"), "http://x", "k"),
                profileService,
                artifactService,
                new InMemoryAuditLogStore(),
                io.micrometer.observation.ObservationRegistry.create());
        orchestrator = new OrchestratorService(chatService, profileService, conversationStore);
    }

    private void seed(List<String> names) {
        for (String name : names) {
            profileService.create(name, "測試", "prompt-" + name, "test-model", 1.0, List.of());
        }
    }

    @Given("已種子化流水線所需的四個 Agent")
    public void seedAll() {
        seed(OrchestratorService.STEP_AGENTS);
    }

    @Given("未種子化 {string}")
    public void seedWithout(String missing) {
        profileService = new AgentProfileService(new InMemoryAgentProfileStore(), null);
        orchestrator = new OrchestratorService(chatService, profileService, conversationStore);
        seed(OrchestratorService.STEP_AGENTS.stream().filter(n -> !n.equals(missing)).toList());
    }

    @Given("流水線模型將依序回覆 Gherkin、BRD JSON、Java 程式碼、審查意見")
    public void scriptedReplies() {
        replies.add(new Scripted(GHERKIN_REPLY, false));
        replies.add(new Scripted("```json\n{\"brdFill\":true}\n```", false));
        replies.add(new Scripted(JAVA_REPLY, false));
        replies.add(new Scripted("審查通過,無重大問題", false));
    }

    @Given("流水線模型將於步驟 1 回覆純文字 {string}")
    public void plainTextStep1(String reply) {
        replies.add(new Scripted(reply, false));
    }

    @Given("流水線模型步驟 3 首次將中斷,重試與其他步驟正常回覆")
    public void step3FailsOnceThenRecovers() {
        replies.add(new Scripted(GHERKIN_REPLY, false));
        replies.add(new Scripted("```json\n{\"brdFill\":true}\n```", false));
        replies.add(new Scripted("```java\n// 部分內容後中斷", true));
        replies.add(new Scripted(JAVA_REPLY, false));
        replies.add(new Scripted("審查通過,無重大問題", false));
    }

    @Given("流水線模型步驟 3 連兩次中斷,其他步驟正常回覆")
    public void step3FailsTwice() {
        replies.add(new Scripted(GHERKIN_REPLY, false));
        replies.add(new Scripted("```json\n{\"brdFill\":true}\n```", false));
        replies.add(new Scripted("```java\n// 部分內容後中斷", true));
        replies.add(new Scripted("```java\n// 又中斷", true));
        replies.add(new Scripted("審查:程式碼不完整", false));
    }

    @When("以目標 {string} 啟動流水線並收完串流")
    public void runPipeline(String goal) {
        conversationId = chatService.createConversation("t", null, null, null, null, null).id();
        OrchestratorService.StartResult r = orchestrator.start(conversationId, goal, null);
        assertEquals(OrchestratorService.STEP_AGENTS, r.steps());
        events = orchestrator.stream(r.messageId()).collectList().block();
        assertNotNull(events);
    }

    @Then("串流應依序出現四個步驟的開始 log")
    public void stepStartLogsInOrder() {
        List<String> startLogs = events.stream()
                .filter(e -> e.type() == SseEventType.LOG)
                .map(e -> (StreamEvent.LogLine) e.payload())
                .filter(l -> "orchestrator".equals(l.source()) && l.msg().contains("開始"))
                .map(StreamEvent.LogLine::msg)
                .toList();
        assertEquals(4, startLogs.size(), "步驟開始 log 數:" + startLogs);
        for (int i = 0; i < 4; i++) {
            assertTrue(startLogs.get(i).startsWith("步驟 " + (i + 1) + "/4"),
                    "第 " + (i + 1) + " 筆:" + startLogs.get(i));
        }
    }

    @Then("對話應有 {int} 則 assistant 訊息")
    public void assistantCount(int expected) {
        long count = conversationStore.findById(conversationId).orElseThrow()
                .messages().stream().filter(m -> m.role() == Role.ASSISTANT).count();
        assertEquals(expected, count);
    }

    @Then("對話應只有 {int} 則 assistant 訊息")
    public void assistantCountExactly(int expected) {
        assistantCount(expected);
    }

    @And("步驟 2 的輸入應包含步驟 1 的 Gherkin 內容")
    public void step2InputHasGherkin() {
        String input = lastUserContent(calls.get(1));
        assertTrue(input.contains("功能: 使用者登入"), "步驟 2 輸入:" + input);
    }

    @And("步驟 4 的輸入應包含步驟 3 的 Java 程式碼")
    public void step4InputHasJava() {
        String input = calls.stream()
                .map(this::lastUserContent)
                .filter(t -> t.startsWith("請審查"))
                .reduce((a, b) -> b)
                .orElse("");
        assertTrue(input.contains("public class App"), "步驟 4 輸入:" + input);
    }

    @Then("串流應含 source 為 {string} 的 WARN 重試 log")
    public void hasRetryWarnLog(String source) {
        boolean found = events.stream()
                .filter(e -> e.type() == SseEventType.LOG)
                .map(e -> (StreamEvent.LogLine) e.payload())
                .anyMatch(l -> source.equals(l.source()) && "WARN".equals(l.level())
                        && l.msg().contains("重試"));
        assertTrue(found, "缺少重試 WARN log");
    }

    @Then("Provider 應被呼叫 {int} 次")
    public void providerCallCount(int expected) {
        assertEquals(expected, calls.size());
    }

    @And("串流應恰有 1 個 done 事件且位於最後")
    public void exactlyOneDoneAtEnd() {
        long doneCount = events.stream().filter(e -> e.type() == SseEventType.DONE).count();
        assertEquals(1, doneCount);
        assertEquals(SseEventType.DONE, events.get(events.size() - 1).type());
    }

    @And("對話應存在 {string} 與 {string} 產出物")
    public void artifactsExist(String type1, String type2) {
        assertFalse(artifactService.versions(conversationId,
                Artifact.ArtifactType.valueOf(type1)).isEmpty(), type1 + " 產出物缺失");
        assertFalse(artifactService.versions(conversationId,
                Artifact.ArtifactType.valueOf(type2)).isEmpty(), type2 + " 產出物缺失");
    }

    @And("串流應含 source 為 {string} 的 ERROR log")
    public void hasOrchestratorError(String source) {
        boolean found = events.stream()
                .filter(e -> e.type() == SseEventType.LOG)
                .map(e -> (StreamEvent.LogLine) e.payload())
                .anyMatch(l -> source.equals(l.source()) && "ERROR".equals(l.level()));
        assertTrue(found);
    }

    @Then("啟動流水線應失敗並提示缺少 {string}")
    public void startFailsMissingAgent(String agentName) {
        String convId = chatService.createConversation("t", null, null, null, null, null).id();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orchestrator.start(convId, "全流程", null));
        assertTrue(ex.getMessage().contains(agentName), ex.getMessage());
    }

    private String lastUserContent(ChatCall call) {
        return call.history().get(call.history().size() - 1).content();
    }
}
