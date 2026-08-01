package com.example.llmagent.application.port.out;

import java.util.List;

import com.example.llmagent.domain.chat.Message;

/**
 * 對 Provider 發起一次串流對話所需的資料(port 輸入)。
 *
 * @param model        模型 id(如 {@code claude-opus-4-8})
 * @param temperature  取樣溫度(可為 {@code null} 用 Provider 預設)
 * @param systemPrompt system prompt(可為 {@code null})
 * @param history      對話歷史(含當前 user 訊息),依時間排序
 * @param tools        啟用的工具名單(Agent Profile tools 欄位;比對工具名稱子字串,
 *                     如 {@code mural} 啟用全部 Mural MCP 工具)。空清單 = 不掛工具
 */
public record ChatCall(
        String model,
        Double temperature,
        String systemPrompt,
        List<Message> history,
        List<String> tools
) {
    public ChatCall(String model, Double temperature, String systemPrompt, List<Message> history) {
        this(model, temperature, systemPrompt, history, List.of());
    }
}
