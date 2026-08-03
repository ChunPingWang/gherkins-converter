package com.example.llmagent.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 對話預設值。預設模型指向 ICA 最新 Claude(claude-opus-4-8)。
 * 全域預設 system prompt 與程式碼/設定檔隔離:未設定屬性時
 * 自 {@code /seed/prompts/default-system-prompt.md} 載入({@link PromptResources})。
 */
@ConfigurationProperties(prefix = "llmagent.chat")
public record ChatProperties(String defaultModelId, String defaultSystemPrompt) {

    public ChatProperties {
        if (defaultModelId == null || defaultModelId.isBlank()) {
            defaultModelId = "claude-opus-4-8";
        }
        if (defaultSystemPrompt == null || defaultSystemPrompt.isBlank()) {
            defaultSystemPrompt = PromptResources.read("default-system-prompt.md");
        }
    }
}
