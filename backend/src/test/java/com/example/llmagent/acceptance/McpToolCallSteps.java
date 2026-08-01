package com.example.llmagent.acceptance;

import java.util.ArrayList;
import java.util.List;

import com.example.llmagent.adapter.out.persistence.InMemoryAgentProfileStore;
import com.example.llmagent.adapter.out.persistence.InMemoryArtifactStore;
import com.example.llmagent.adapter.out.persistence.InMemoryAuditLogStore;
import com.example.llmagent.adapter.out.persistence.InMemoryConversationStore;
import com.example.llmagent.application.AgentProfileService;
import com.example.llmagent.application.ArtifactService;
import com.example.llmagent.application.ChatProperties;
import com.example.llmagent.application.ChatService;
import com.example.llmagent.application.RuntimeSettingsService;
import com.example.llmagent.application.event.StreamEvent;
import com.example.llmagent.application.port.out.ChatCall;
import com.example.llmagent.application.port.out.ChatModelPort;
import com.example.llmagent.domain.chat.ChatChunk;
import com.example.llmagent.domain.chat.Usage;
import com.example.llmagent.domain.sse.SseEventType;

import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MCP 工具呼叫串流 steps。以可記錄 ChatCall 的 fake provider 確保決定性(不啟動真實 MCP)。 */
public class McpToolCallSteps {

    /** fake provider:記錄最後一次 ChatCall,依腳本回放 chunk(含 tool_call 片段)。 */
    private ChatCall lastCall;
    private List<ChatChunk> scriptedChunks;

    private ChatService chatService;
    private AgentProfileService profileService;
    private String profileId;
    private List<StreamEvent> events;

    @Before
    public void setup() {
        scriptedChunks = List.of();
        lastCall = null;
        profileId = null;
        profileService = new AgentProfileService(new InMemoryAgentProfileStore(), null);
        ChatModelPort recordingPort = call -> {
            lastCall = call;
            return Flux.fromIterable(scriptedChunks);
        };
        chatService = new ChatService(
                recordingPort,
                new InMemoryConversationStore(),
                new RuntimeSettingsService(new ChatProperties("test-model", "sys"), "http://x", "k"),
                profileService,
                new ArtifactService(new InMemoryArtifactStore()),
                new InMemoryAuditLogStore(),
                io.micrometer.observation.ObservationRegistry.create());
    }

    @Given("存在啟用工具 {string} 的 Agent Profile {string}")
    public void profileWithTool(String tool, String name) {
        profileId = profileService.create(name, "測試", "你可讀取 Mural 看板",
                "test-model", 1.0, List.of(tool)).id();
    }

    @Given("存在未啟用工具的 Agent Profile {string}")
    public void profileWithoutTools(String name) {
        profileId = profileService.create(name, "測試", "純文字",
                "test-model", 1.0, List.of()).id();
    }

    @And("模型將先呼叫工具 {string} 再回覆 {string}")
    public void modelCallsToolThenReplies(String tool, String reply) {
        scriptedChunks = List.of(
                ChatChunk.tool(new ChatChunk.ToolCall(tool, "{\"muralId\":\"m1\"}", "started")),
                ChatChunk.tool(new ChatChunk.ToolCall(tool, "{\"muralId\":\"m1\"}", "finished")),
                ChatChunk.text(reply),
                ChatChunk.finalUsage(new Usage(10, 5)));
    }

    @And("模型呼叫工具 {string} 將失敗後回覆 {string}")
    public void modelToolFailsThenReplies(String tool, String reply) {
        scriptedChunks = List.of(
                ChatChunk.tool(new ChatChunk.ToolCall(tool, "{}", "started")),
                ChatChunk.tool(new ChatChunk.ToolCall(tool, "{}", "error")),
                ChatChunk.text(reply),
                ChatChunk.finalUsage(new Usage(10, 5)));
    }

    @Given("模型將以純文字回覆 {string}")
    public void modelPlainReply(String reply) {
        scriptedChunks = List.of(
                ChatChunk.text(reply),
                ChatChunk.finalUsage(new Usage(10, 5)));
    }

    @When("以該 Agent Profile 送出訊息 {string}")
    public void sendWithProfile(String content) {
        var conv = chatService.createConversation("t", null, null, null, profileId, null);
        String messageId = chatService.addUserMessage(conv.id(), content, null, profileId, null);
        events = chatService.streamAssistant(messageId).collectList().block();
        assertNotNull(events);
    }

    @When("未指定 Agent Profile 送出訊息 {string}")
    public void sendWithoutProfile(String content) {
        var conv = chatService.createConversation("t", null, null, null, null, null);
        String messageId = chatService.addUserMessage(conv.id(), content);
        events = chatService.streamAssistant(messageId).collectList().block();
        assertNotNull(events);
    }

    @Then("串流應依序出現 {string} 再 {string} 再 {string}")
    public void eventsInOrder(String first, String second, String third) {
        int i1 = firstIndexOf(first);
        int i2 = firstIndexOf(second);
        int i3 = firstIndexOf(third);
        assertTrue(i1 >= 0 && i2 > i1 && i3 > i2,
                "期望 " + first + " < " + second + " < " + third
                        + ",實際索引 " + i1 + "/" + i2 + "/" + i3);
    }

    @Then("tool_call 事件應依序為 {string} 之 {string} 與 {string}")
    public void toolCallSequence(String tool, String status1, String status2) {
        List<StreamEvent.ToolCallEvent> calls = new ArrayList<>();
        for (StreamEvent e : events) {
            if (e.type() == SseEventType.TOOL_CALL) {
                calls.add((StreamEvent.ToolCallEvent) e.payload());
            }
        }
        assertEquals(2, calls.size(), "tool_call 事件數");
        assertEquals(tool, calls.get(0).name());
        assertEquals(status1, calls.get(0).status());
        assertEquals(tool, calls.get(1).name());
        assertEquals(status2, calls.get(1).status());
    }

    @Then("串流內容合併後應為 {string}")
    public void mergedContent(String expected) {
        StringBuilder sb = new StringBuilder();
        for (StreamEvent e : events) {
            if (e.type() == SseEventType.CONTENT) {
                sb.append(((StreamEvent.ContentDelta) e.payload()).delta());
            }
        }
        assertEquals(expected, sb.toString());
    }

    @Then("應有一筆 source 為 {string} 的 log 事件")
    public void hasLogWithSource(String source) {
        boolean found = events.stream()
                .filter(e -> e.type() == SseEventType.LOG)
                .map(e -> (StreamEvent.LogLine) e.payload())
                .anyMatch(l -> source.equals(l.source()));
        assertTrue(found, "缺少 source=" + source + " 的 log 事件");
    }

    @Then("傳給 Provider 的工具名單應為空")
    public void providerToolsEmpty() {
        assertNotNull(lastCall);
        assertTrue(lastCall.tools().isEmpty(), "工具名單應為空,實際:" + lastCall.tools());
    }

    @Then("傳給 Provider 的工具名單應為 {string}")
    public void providerToolsIs(String tool) {
        assertNotNull(lastCall);
        assertEquals(List.of(tool), lastCall.tools());
    }

    private int firstIndexOf(String wireName) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).type().wireName().equals(wireName)) {
                return i;
            }
        }
        return -1;
    }
}
