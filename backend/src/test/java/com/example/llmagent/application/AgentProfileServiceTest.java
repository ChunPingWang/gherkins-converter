package com.example.llmagent.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.llmagent.adapter.out.persistence.InMemoryAgentProfileStore;
import com.example.llmagent.domain.agent.AgentProfile;
import com.example.llmagent.domain.agent.PromptTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WP2-T3/T4 驗收:prompt 修改產生新 version、舊版可查;範本缺變數明確錯誤。 */
class AgentProfileServiceTest {

    private final AgentProfileService service = new AgentProfileService(new InMemoryAgentProfileStore(), null);

    @Test
    void updateAppendsNewVersionAndKeepsHistory() {
        AgentProfile v1 = service.create("BDD Agent", "d", "prompt v1", "claude-opus-4-8", 1.0, List.of());
        assertThat(v1.version()).isEqualTo(1);

        AgentProfile v2 = service.update(v1.id(), null, null, "prompt v2", null, null, null);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.systemPrompt()).isEqualTo("prompt v2");
        assertThat(v2.name()).isEqualTo("BDD Agent"); // 未指定欄位沿用

        List<AgentProfile> versions = service.versions(v1.id());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(2); // 新→舊
        assertThat(versions.get(1).systemPrompt()).isEqualTo("prompt v1"); // 舊版可查

        assertThat(service.listLatest()).extracting(AgentProfile::version).containsExactly(2);
    }

    @Test
    void rendersTemplateVariables() {
        AgentProfile p = service.create("t", null,
                "專案 {project_name},Gherkin 使用 {gherkin_locale}。", "m", 1.0, List.of());
        String rendered = service.renderPrompt(p.id(),
                Map.of("project_name", "訂購平台", "gherkin_locale", "zh-TW"));
        assertThat(rendered).isEqualTo("專案 訂購平台,Gherkin 使用 zh-TW。");
    }

    @Test
    void missingVariablesThrowWithAllNames() {
        AgentProfile p = service.create("t", null,
                "{project_name} / {gherkin_locale}", "m", 1.0, List.of());
        assertThatThrownBy(() -> service.renderPrompt(p.id(), Map.of("project_name", "x")))
                .isInstanceOf(PromptTemplate.MissingVariableException.class)
                .hasMessageContaining("gherkin_locale");
    }
}
