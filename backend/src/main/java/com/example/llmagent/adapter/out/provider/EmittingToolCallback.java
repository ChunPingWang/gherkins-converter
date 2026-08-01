package com.example.llmagent.adapter.out.provider;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import com.example.llmagent.domain.chat.ChatChunk;

/**
 * 包裝 Spring AI {@link ToolCallback}:於工具執行前後發出 tool_call 進度片段
 * (started / finished / error,ADR-003),由 adapter 併入串流供前端即時顯示。
 */
public class EmittingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final Consumer<ChatChunk.ToolCall> emitter;

    public EmittingToolCallback(ToolCallback delegate, Consumer<ChatChunk.ToolCall> emitter) {
        this.delegate = delegate;
        this.emitter = emitter;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return emitAround(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return emitAround(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String emitAround(String toolInput, Supplier<String> invocation) {
        String name = getToolDefinition().name();
        emitter.accept(new ChatChunk.ToolCall(name, toolInput, "started"));
        try {
            String result = invocation.get();
            emitter.accept(new ChatChunk.ToolCall(name, toolInput, "finished"));
            return result;
        } catch (RuntimeException e) {
            emitter.accept(new ChatChunk.ToolCall(name, toolInput, "error"));
            throw e;
        }
    }
}
