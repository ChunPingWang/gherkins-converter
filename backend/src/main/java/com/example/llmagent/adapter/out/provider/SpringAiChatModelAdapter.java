package com.example.llmagent.adapter.out.provider;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.example.llmagent.application.RuntimeSettingsService;
import com.example.llmagent.application.port.out.ChatCall;
import com.example.llmagent.application.port.out.ChatModelPort;
import com.example.llmagent.domain.chat.ChatChunk;
import com.example.llmagent.domain.chat.Message;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * ICA(OpenAI-Compatible)Provider adapter,透過 Spring AI {@link OpenAiChatModel}
 * ({@code ChatModel} 介面)串流呼叫(ADR-001、CLAUDE.md #3)。
 *
 * <p>連線參數(base URL / API key)來自 {@link RuntimeSettingsService},可於執行期修改;
 * 設定版本變更時延遲重建底層 client。
 */
@Component
public class SpringAiChatModelAdapter implements ChatModelPort {

    private final RuntimeSettingsService settings;
    private final ObjectProvider<ToolCallbackProvider> toolCallbacks;

    private volatile OpenAiChatModel chatModel;
    private volatile long builtVersion = -1;

    public SpringAiChatModelAdapter(RuntimeSettingsService settings,
                                    ObjectProvider<ToolCallbackProvider> toolCallbacks) {
        this.settings = settings;
        this.toolCallbacks = toolCallbacks;
    }

    private OpenAiChatModel model() {
        long v = settings.version();
        OpenAiChatModel m = chatModel;
        if (m == null || builtVersion != v) {
            synchronized (this) {
                if (chatModel == null || builtVersion != settings.version()) {
                    OpenAiApi api = OpenAiApi.builder()
                            .baseUrl(settings.baseUrl())
                            .apiKey(settings.apiKey())
                            .build();
                    chatModel = OpenAiChatModel.builder()
                            .openAiApi(api)
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(settings.defaultModelId())
                                    // ICA(litellm)對 Claude 僅接受 temperature=1
                                    .temperature(1.0)
                                    // 避免完整程式碼/文件被預設 4096 截斷
                                    .maxTokens(32000)
                                    .build())
                            .build();
                    builtVersion = settings.version();
                }
                m = chatModel;
            }
        }
        return m;
    }

    @Override
    public Flux<ChatChunk> stream(ChatCall call) {
        // MCP 工具探索(listTools)為 blocking 呼叫,不可在 Netty event loop 執行;
        // 整段組裝移至 boundedElastic(defer 供應器於訂閱執行緒執行)。
        return Flux.defer(() -> {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .model(call.model())
                    .streamUsage(true);
            if (call.temperature() != null) {
                options.temperature(call.temperature());
            }

            // 工具掛載(MCP):依 Agent Profile tools 名單挑選,包裝為進度發射器;
            // 工具由 Spring AI 於串流中內部執行,進度片段經 sink 併入回傳串流。
            Sinks.Many<ChatChunk> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
            List<ToolCallback> selected = selectTools(call.tools(), toolEvents);
            if (!selected.isEmpty()) {
                options.toolCallbacks(selected);
            }

            Prompt prompt = new Prompt(toSpringMessages(call), options.build());
            Flux<ChatChunk> modelStream = model().stream(prompt)
                    .map(this::toChunk)
                    .doFinally(sig -> toolEvents.tryEmitComplete());
            return Flux.merge(modelStream, toolEvents.asFlux());
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** 以 tools 名單(子字串、不分大小寫)過濾已註冊的 MCP 工具;無 MCP client 時回空。 */
    private List<ToolCallback> selectTools(List<String> enabled, Sinks.Many<ChatChunk> sink) {
        if (enabled == null || enabled.isEmpty()) {
            return List.of();
        }
        ToolCallbackProvider provider = toolCallbacks.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        List<ToolCallback> selected = new ArrayList<>();
        for (ToolCallback cb : provider.getToolCallbacks()) {
            String name = cb.getToolDefinition().name().toLowerCase();
            boolean match = enabled.stream()
                    .anyMatch(t -> t != null && !t.isBlank() && name.contains(t.trim().toLowerCase()));
            if (match) {
                selected.add(new EmittingToolCallback(cb,
                        tc -> sink.tryEmitNext(ChatChunk.tool(tc))));
            }
        }
        return selected;
    }

    private List<org.springframework.ai.chat.messages.Message> toSpringMessages(ChatCall call) {
        List<org.springframework.ai.chat.messages.Message> msgs = new ArrayList<>();
        if (call.systemPrompt() != null && !call.systemPrompt().isBlank()) {
            msgs.add(new SystemMessage(call.systemPrompt()));
        }
        for (Message m : call.history()) {
            switch (m.role()) {
                case USER -> msgs.add(new UserMessage(m.content()));
                case ASSISTANT -> msgs.add(new AssistantMessage(m.content()));
                case SYSTEM -> msgs.add(new SystemMessage(m.content()));
            }
        }
        return msgs;
    }

    private ChatChunk toChunk(ChatResponse response) {
        String text = "";
        if (response.getResult() != null && response.getResult().getOutput() != null) {
            String t = response.getResult().getOutput().getText();
            if (t != null) {
                text = t;
            }
        }
        com.example.llmagent.domain.chat.Usage usage = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Usage u = response.getMetadata().getUsage();
            int total = intValue(u.getTotalTokens());
            if (total > 0) {
                usage = new com.example.llmagent.domain.chat.Usage(
                        intValue(u.getPromptTokens()), intValue(u.getCompletionTokens()));
            }
        }
        return new ChatChunk(text, usage);
    }

    private static int intValue(Number v) {
        return v == null ? 0 : v.intValue();
    }
}
