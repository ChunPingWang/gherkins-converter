package com.example.llmagent.application.event;

import com.example.llmagent.domain.sse.SseEventType;

/**
 * 應用層串流事件。{@code payload} 為對應型別的 record,由 web adapter 序列化為 SSE data(JSON)。
 * 事件型別對映 ADR-003 五型。
 */
public record StreamEvent(SseEventType type, Object payload) {

    public static StreamEvent thinking(String delta) {
        return new StreamEvent(SseEventType.THINKING, new ThinkingDelta(delta));
    }

    public static StreamEvent content(String delta) {
        return new StreamEvent(SseEventType.CONTENT, new ContentDelta(delta));
    }

    public static StreamEvent log(String level, String source, String msg, String ts) {
        return new StreamEvent(SseEventType.LOG, new LogLine(level, source, msg, ts));
    }

    public static StreamEvent done(DoneInfo info) {
        return new StreamEvent(SseEventType.DONE, info);
    }

    public static StreamEvent toolCall(String name, Object arguments, String status) {
        return new StreamEvent(SseEventType.TOOL_CALL, new ToolCallEvent(name, arguments, status));
    }

    // ---- payload records(對映 openapi.yaml SSE schema)----

    public record ThinkingDelta(String delta) {
    }

    public record ContentDelta(String delta) {
    }

    public record LogLine(String level, String source, String msg, String ts) {
    }

    /** tool_call 事件(openapi:{name, arguments: object, status: started|finished|error})。 */
    public record ToolCallEvent(String name, Object arguments, String status) {
    }

    public record UsageInfo(int promptTokens, int completionTokens) {
    }

    public record DoneInfo(UsageInfo usage, long elapsedMs, long ttftMs) {
    }
}
