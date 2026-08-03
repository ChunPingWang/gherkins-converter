package com.example.llmagent.acceptance;

import java.util.List;

import com.example.llmagent.adapter.out.persistence.InMemoryAgentProfileStore;
import com.example.llmagent.application.AgentProfileService;
import com.example.llmagent.application.AgentRouterService;
import com.example.llmagent.application.AgentRouterService.RouteDecision;
import com.example.llmagent.application.ChatProperties;
import com.example.llmagent.application.RuntimeSettingsService;
import com.example.llmagent.application.port.out.ChatCall;
import com.example.llmagent.application.port.out.ChatModelPort;
import com.example.llmagent.domain.chat.ChatChunk;

import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agent 自動路由 steps。fake provider 回放路由回覆,離線決定性執行。 */
public class AgentRoutingSteps {

    private AgentProfileService profileService;
    private AgentRouterService router;
    private String cannedReply;
    private ChatCall lastCall;
    private String bddProfileId;
    private RouteDecision decision;

    @Before
    public void setup() {
        cannedReply = "";
        lastCall = null;
        profileService = new AgentProfileService(new InMemoryAgentProfileStore(), null);
        ChatModelPort recordingPort = call -> {
            lastCall = call;
            return Flux.just(ChatChunk.text(cannedReply));
        };
        router = new AgentRouterService(recordingPort, profileService,
                new RuntimeSettingsService(new ChatProperties("test-model", "sys"), "http://x", "k"));
    }

    @Given("存在候選 Agent {string} 與 {string}")
    public void candidates(String name1, String name2) {
        bddProfileId = profileService.create(name1, "需求轉 Gherkin", "p1", "test-model", 1.0, List.of()).id();
        profileService.create(name2, "產生 Java 程式碼", "p2", "test-model", 1.0, List.of());
    }

    @Given("路由模型將回傳 BDD Agent 的 JSON 決策且信心為 {double} 理由 {string}")
    public void modelReturnsBddJson(double confidence, String reason) {
        cannedReply = "{\"agentProfileId\":\"" + bddProfileId + "\",\"confidence\":" + confidence
                + ",\"reason\":\"" + reason + "\"}";
    }

    @Given("路由模型將回傳含 think 標籤包裹的 BDD Agent JSON 決策")
    public void modelReturnsThinkWrappedJson() {
        cannedReply = "<think>使用者要 Gherkin {嗯}</think>{\"agentProfileId\":\"" + bddProfileId
                + "\",\"confidence\":0.8,\"reason\":\"Gherkin\"}";
    }

    @Given("路由模型將回傳 none 決策")
    public void modelReturnsNone() {
        cannedReply = "{\"agentProfileId\":\"none\",\"confidence\":0,\"reason\":\"閒聊\"}";
    }

    @Given("路由模型將回傳 {string}")
    public void modelReturnsRaw(String reply) {
        cannedReply = reply;
    }

    @Given("路由模型將回傳 pipeline 決策且信心為 {double}")
    public void modelReturnsPipeline(double confidence) {
        cannedReply = "{\"agentProfileId\":\"pipeline\",\"confidence\":" + confidence
                + ",\"reason\":\"要求全流程\"}";
    }

    @Given("路由模型將回傳未知 Agent id 的 JSON 決策")
    public void modelReturnsUnknownId() {
        cannedReply = "{\"agentProfileId\":\"no-such-id\",\"confidence\":0.9,\"reason\":\"x\"}";
    }

    @When("對訊息 {string} 進行路由")
    public void routeMessage(String message) {
        decision = router.route(message).block();
        assertNotNull(decision);
    }

    @Then("路由決策 target 應為 {string} 且對應 {string}")
    public void decisionTargetAndAgent(String target, String agentName) {
        assertEquals(target, decision.target().name());
        assertEquals(agentName, decision.agentName());
    }

    @Then("路由決策 target 應為 {string}")
    public void decisionTarget(String target) {
        assertEquals(target, decision.target().name());
    }

    @And("路由決策信心應為 {double} 且理由為 {string}")
    public void decisionConfidenceAndReason(double confidence, String reason) {
        assertEquals(confidence, decision.confidence(), 0.0001);
        assertEquals(reason, decision.reason());
    }

    @Then("路由呼叫傳給 Provider 的工具名單應為空")
    public void routeCallHasNoTools() {
        assertNotNull(lastCall);
        assertTrue(lastCall.tools().isEmpty());
    }
}
