package com.example.llmagent.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * System Prompt 資源檔載入器。所有內建 prompt 與程式碼隔離,
 * 統一放在 {@code classpath:/seed/prompts/*.md};DB 端的版本控制由
 * {@link AgentProfileSeeder} 依檔案內容變更自動 append 新版本。
 */
public final class PromptResources {

    private PromptResources() {
    }

    /** 讀取 /seed/prompts/ 下的 prompt 檔;缺檔視為建置錯誤,直接失敗。 */
    public static String read(String filename) {
        String path = "/seed/prompts/" + filename;
        try (InputStream in = PromptResources.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到 prompt 資源檔: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("讀取 prompt 資源檔失敗: " + path, e);
        }
    }
}
