package com.example.llmagent.adapter.out.provider;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import com.example.llmagent.domain.sse.SseEventType;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP8-T2:Provider 相容性矩陣 —— WireMock 模擬 OpenAI-compatible 串流
 * (thinking 標籤跨 chunk、純內容、usage 尾 chunk),經真實 SpringAiChatModelAdapter
 * + ChatService 全管線驗證五型事件分流正確。
 */
class ProviderCompatibilityTest {

    private WireMockServer wm;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        RuntimeSettingsService settings = new RuntimeSettingsService(
                new ChatProperties("test-model", "sys"), wm.baseUrl(), "test-key");
        chatService = new ChatService(
                new SpringAiChatModelAdapter(settings, noToolCallbacks()),
                new InMemoryConversationStore(),
                settings,
                new AgentProfileService(new InMemoryAgentProfileStore(), null),
                new ArtifactService(new InMemoryArtifactStore()),
                new InMemoryAuditLogStore(),
                io.micrometer.observation.ObservationRegistry.create());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    /** 無 MCP client 環境:ToolCallbackProvider bean 不存在。 */
    private static org.springframework.beans.factory.ObjectProvider<org.springframework.ai.tool.ToolCallbackProvider> noToolCallbacks() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public org.springframework.ai.tool.ToolCallbackProvider getObject() {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                        org.springframework.ai.tool.ToolCallbackProvider.class);
            }

            @Override
            public org.springframework.ai.tool.ToolCallbackProvider getIfAvailable() {
                return null;
            }

            @Override
            public org.springframework.ai.tool.ToolCallbackProvider getIfUnique() {
                return null;
            }

            @Override
            public org.springframework.ai.tool.ToolCallbackProvider getObject(Object... args) {
                return getObject();
            }
        };
    }

    private void stubStream(String... contentDeltas) {
        StringBuilder body = new StringBuilder();
        for (String delta : contentDeltas) {
            body.append("data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",")
                    .append("\"choices\":[{\"index\":0,\"delta\":{\"content\":")
                    .append(json(delta)).append(",\"role\":\"assistant\"}}]}\n\n");
        }
        body.append("data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"choices\":[],")
                .append("\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}\n\n");
        body.append("data: [DONE]\n\n");
        wm.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(body.toString())));
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private List<StreamEvent> run() {
        var conv = chatService.createConversation("t", null, null, null, null, null);
        String mid = chatService.addUserMessage(conv.id(), "hi");
        return chatService.streamAssistant(mid).collectList().block();
    }

    private String joined(List<StreamEvent> events, SseEventType type) {
        StringBuilder sb = new StringBuilder();
        for (StreamEvent e : events) {
            if (e.type() == type) {
                Object p = e.payload();
                if (p instanceof StreamEvent.ThinkingDelta t) sb.append(t.delta());
                if (p instanceof StreamEvent.ContentDelta c) sb.append(c.delta());
            }
        }
        return sb.toString();
    }

    @Test
    void thinkingTagSplitAcrossChunks() {
        stubStream("<thi", "nk>深度思考</thi", "nk>這是答案");
        List<StreamEvent> events = run();
        assertThat(joined(events, SseEventType.THINKING)).isEqualTo("深度思考");
        assertThat(joined(events, SseEventType.CONTENT)).isEqualTo("這是答案");
    }

    @Test
    void plainStreamingWithUsage() {
        stubStream("你好", ",世界");
        List<StreamEvent> events = run();
        assertThat(joined(events, SseEventType.CONTENT)).isEqualTo("你好,世界");
        StreamEvent done = events.get(events.size() - 1);
        assertThat(done.type()).isEqualTo(SseEventType.DONE);
        var info = (StreamEvent.DoneInfo) done.payload();
        assertThat(info.usage().promptTokens()).isEqualTo(3);
        assertThat(info.usage().completionTokens()).isEqualTo(4);
    }

    @Test
    void qwen3StyleUnclosedThinkIsTolerated() {
        stubStream("<think>只想不說"); // 未閉合(reasoning 模型異常樣本)
        List<StreamEvent> events = run();
        assertThat(joined(events, SseEventType.THINKING)).isEqualTo("只想不說");
        assertThat(events.get(events.size() - 1).type()).isEqualTo(SseEventType.DONE);
    }
}
