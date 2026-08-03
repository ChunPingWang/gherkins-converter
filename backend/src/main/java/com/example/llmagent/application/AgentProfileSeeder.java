package com.example.llmagent.application;

import java.util.List;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 內建 Agent Profile 種子化(規劃書 §4.5、ADR-006)。
 *
 * <p>所有 System Prompt 與程式碼隔離,存於 {@code /seed/prompts/*.md}
 * ({@link PromptResources});每個 Profile 以**名稱**冪等種子化:
 * 不存在則建立(v1),存在且 prompt 檔內容與 DB 最新版不同則自動 append 新版本
 * —— 檔案為 prompt 主來源,DB 版本鏈完整記錄每次變更,產出物可追溯至當版 prompt。
 * UI(Agent Profile 管理)修改亦 append 新版本,同一條版本鏈。
 */
@Configuration
public class AgentProfileSeeder {

    /** 內建 Profile 種子規格:名稱 → prompt 檔與預設參數。 */
    record SeedSpec(String name, String description, String promptFile,
                    String defaultModelId, Double temperature, List<String> tools) {
    }

    private static final List<SeedSpec> SEEDS = List.of(
            new SeedSpec("BDD 規格 Agent",
                    "將需求情境轉為 zh-TW Gherkin(正向 + 反向);可讀取 Mural 看板",
                    "bdd-spec-agent.md", "claude-opus-4-8", 1.0, List.of("mural")),
            new SeedSpec("DDD 設計 Agent",
                    "Plan 階段:依 Gherkin 產出 DDD 技術計畫(Bounded Context 劃分、聚合根/Command/Domain Event、技術決策)",
                    "plan-agent.md", "claude-opus-4-8", 1.0, List.of()),
            new SeedSpec("Java 產碼 Agent",
                    "依 Gherkin/需求產生 Java 21 + Cucumber(DDD/SOLID,涵蓋率 ≥ 80%)",
                    "java-codegen-agent.md", "claude-opus-4-8", 1.0, List.of()),
            new SeedSpec("Code Review Agent",
                    "審查程式碼:正確性、DDD/SOLID、測試涵蓋",
                    "code-review-agent.md", "claude-opus-4-8", 1.0, List.of()),
            new SeedSpec("BRD 業務文件 Agent",
                    "步驟二:依 Gherkin 產出 BRD 套版資料(JSON),系統於原 Word 模板填寫,樣式完全保留",
                    "brd-fill-agent.md", "claude-opus-4-8", 1.0, List.of()));

    @Bean
    public ApplicationRunner seedAgentProfiles(AgentProfileService service) {
        return args -> SEEDS.forEach(spec ->
                seedProfile(service, spec.name(), spec.description(),
                        PromptResources.read(spec.promptFile()),
                        spec.defaultModelId(), spec.temperature(), spec.tools()));
    }

    /** 單一 Profile 冪等種子化;prompt 內容變更時 append 新版本(公開供測試注入內容)。 */
    public void seedProfile(AgentProfileService service, String name, String description,
                            String systemPrompt, String defaultModelId,
                            Double temperature, List<String> tools) {
        var existing = service.listLatest().stream()
                .filter(p -> name.equals(p.name()))
                .findFirst();
        if (existing.isEmpty()) {
            service.create(name, description, systemPrompt, defaultModelId, temperature, tools);
        } else if (!systemPrompt.equals(existing.get().systemPrompt())) {
            service.update(existing.get().id(), null, null, systemPrompt, null, null, null);
        }
    }
}
