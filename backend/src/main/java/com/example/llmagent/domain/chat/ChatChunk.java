package com.example.llmagent.domain.chat;

import org.springframework.lang.Nullable;

/**
 * Provider 串流輸出的單一片段(domain 層,與 Provider 技術無關)。
 *
 * @param textDelta 本片段的文字增量(可能為空字串)
 * @param usage     僅最終片段帶有 token 用量;其餘為 {@code null}
 * @param toolCall  工具呼叫進度片段(ADR-003 tool_call 事件);非工具片段為 {@code null}
 */
public record ChatChunk(String textDelta, @Nullable Usage usage, @Nullable ToolCall toolCall) {

    /** 工具呼叫進度。{@code status} 為 started / finished / error(openapi tool_call schema)。 */
    public record ToolCall(String name, String argumentsJson, String status) {
    }

    public ChatChunk(String textDelta, @Nullable Usage usage) {
        this(textDelta, usage, null);
    }

    public static ChatChunk text(String delta) {
        return new ChatChunk(delta, null, null);
    }

    public static ChatChunk finalUsage(Usage usage) {
        return new ChatChunk("", usage, null);
    }

    public static ChatChunk tool(ToolCall toolCall) {
        return new ChatChunk("", null, toolCall);
    }
}
