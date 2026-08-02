package com.example.llmagent.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 內建 Agent Profile(規劃書 §4.5):BDD 規格 / Java 產碼 / Code Review(儲存為空時種子化),
 * 以及步驟二「BRD 業務文件 Agent」(以名稱冪等種子化;prompt 內容變更時自動 append 新版本。
 * System Prompt 指示模型輸出 BRD 套版 JSON,由 /api/docx/fill 於原模板上填寫,樣式保留)。
 */
@Configuration
public class AgentProfileSeeder {

    /** 步驟二(Gherkin 轉業務文件)內建 Profile 名稱;以名稱判斷是否已種子化。 */
    public static final String BRD_PROFILE_NAME = "BRD 業務文件 Agent";

    private static final String COMMON_RULES = """
            【最重要 — 範圍限制】只輸出使用者「當前這則訊息」明確要求的產物,不要主動附加未被要求的內容。
            多個步驟請分次進行,勿在前一步驟就預先產生後續步驟的產物。回答精確、聚焦。""";

    @Bean
    public ApplicationRunner seedAgentProfiles(AgentProfileService service) {
        return args -> {
            if (service.listLatest().isEmpty()) {
                seedBaseProfiles(service);
            }
            seedBrdProfile(service);
        };
    }

    private void seedBaseProfiles(AgentProfileService service) {
        service.create("BDD 規格 Agent",
                "將需求情境轉為 zh-TW Gherkin(正向 + 反向)",
                COMMON_RULES + """

                        你專精 BDD。產生 Gherkin 時使用 {gherkin_locale} 關鍵字(功能:、場景:、場景大綱:、假設、當、那麼、而且、但是),
                        同時涵蓋正向與反向情境;以 ```gherkin 區塊呈現,第一行以註解標明 # language: zh-TW 與檔名。
                        專案名稱:{project_name}。
                        你可使用 Mural 工具讀取線上白板(看板、便利貼);使用者提及 Mural 看板時,
                        先以工具取得內容再轉為需求與 Gherkin。
                        【Mural 看板判讀規約】讀取看板時:以 y 座標分群,每一橫排=一個情境;
                        同排內依 x 座標由左而右為步驟順序;每張橘色便利貼(Use Case/Domain Event)
                        對應情境中的一個步驟。
                        【微服務對應表】(僅在使用者明確要求微服務設計/對應表時適用)
                        每個微服務一節,以表格呈現:聚合根(Aggregate Root)| 命令(Command)| 領域事件(Domain Event);
                        事件名稱須與看板便利貼文字逐字一致,不得改寫。""",
                "claude-opus-4-8", 1.0, List.of("mural"));
        service.create("Java 產碼 Agent",
                "依 Gherkin/需求產生 Java 21 + Cucumber(DDD/SOLID,涵蓋率 ≥ 80%)",
                COMMON_RULES + """

                        你專精 Java 21、Cucumber、DDD 與 SOLID。產生程式碼時每個檔案獨立一個 code fence,
                        首行以註解標明相對路徑;遵循 DDD 分層(domain / application / infrastructure)與 SOLID;
                        Cucumber step definitions 使用英文 annotation(@Given/@When/@Then/@And,io.cucumber.java.en.*),
                        feature 檔保留繁體中文;提供 JUnit 5 runner 與單元測試,涵蓋率 ≥ 80%;實作完整不省略。""",
                "claude-opus-4-8", 1.0, List.of());
        service.create("Code Review Agent",
                "審查程式碼:正確性、DDD/SOLID、測試涵蓋",
                COMMON_RULES + """

                        你是資深 reviewer。針對提供的程式碼指出:正確性問題(附重現情境)、
                        DDD/SOLID 違反、缺漏的測試;以嚴重度排序,每項附具體修正建議與檔案位置。""",
                "claude-opus-4-8", 1.0, List.of());
    }

    /** 步驟二 BRD Profile:輸出套版 JSON(/api/docx/fill 據以於原模板填寫);prompt 變更時 append 新版本。 */
    void seedBrdProfile(AgentProfileService service) {
        String systemPrompt = readResource("/seed/brd-fill-prompt.md");
        var existing = service.listLatest().stream()
                .filter(p -> BRD_PROFILE_NAME.equals(p.name()))
                .findFirst();
        if (existing.isEmpty()) {
            service.create(BRD_PROFILE_NAME,
                    "步驟二:依 Gherkin 產出 BRD 套版資料(JSON),系統於原 Word 模板填寫,樣式完全保留",
                    systemPrompt, "claude-opus-4-8", 1.0, List.of());
        } else if (!systemPrompt.equals(existing.get().systemPrompt())) {
            service.update(existing.get().id(), null, null, systemPrompt, null, null, null);
        }
    }

    private String readResource(String path) {
        return new String(readResourceBytes(path), StandardCharsets.UTF_8);
    }

    private byte[] readResourceBytes(String path) {
        try (InputStream in = AgentProfileSeeder.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到內建資源: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("讀取內建資源失敗: " + path, e);
        }
    }
}
