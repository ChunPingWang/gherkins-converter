package com.example.llmagent.acceptance;

import java.util.List;

import com.example.llmagent.adapter.out.persistence.InMemoryAgentProfileStore;
import com.example.llmagent.application.AgentProfileSeeder;
import com.example.llmagent.application.AgentProfileService;
import com.example.llmagent.domain.agent.AgentProfile;

import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** System Prompt 檔案隔離與版本控制 steps(種子化冪等/內容變更出版/還原)。 */
public class PromptManagementSteps {

    private AgentProfileService service;
    private AgentProfileSeeder seeder;

    @Before
    public void setup() {
        service = new AgentProfileService(new InMemoryAgentProfileStore(), null);
        seeder = new AgentProfileSeeder();
    }

    @Given("以內容 {string} 種子化名為 {string} 的內建 Profile")
    public void seedWithContent(String prompt, String name) {
        seeder.seedProfile(service, name, "測試", prompt, "test-model", 1.0, List.of());
    }

    @And("以內容 {string} 再次種子化同名 Profile")
    public void seedAgainWithContent(String prompt) {
        String name = service.listLatest().get(0).name();
        seeder.seedProfile(service, name, "測試", prompt, "test-model", 1.0, List.of());
    }

    @When("還原 {string} 至版本 {int}")
    public void restoreVersion(String name, int version) {
        service.restore(byName(name).id(), version);
    }

    @Then("{string} 的最新版本應為 {int} 且 prompt 為 {string}")
    public void latestVersionAndPrompt(String name, int version, String prompt) {
        AgentProfile p = byName(name);
        assertEquals(version, p.version());
        assertEquals(prompt, p.systemPrompt());
    }

    @And("{string} 的版本歷史應有 {int} 筆")
    public void versionHistoryCount(String name, int count) {
        assertEquals(count, service.versions(byName(name).id()).size());
    }

    @Then("還原 {string} 至版本 {int} 應失敗")
    public void restoreFails(String name, int version) {
        String id = byName(name).id();
        assertThrows(IllegalArgumentException.class, () -> service.restore(id, version));
    }

    private AgentProfile byName(String name) {
        return service.listLatest().stream()
                .filter(p -> name.equals(p.name()))
                .findFirst().orElseThrow();
    }
}
