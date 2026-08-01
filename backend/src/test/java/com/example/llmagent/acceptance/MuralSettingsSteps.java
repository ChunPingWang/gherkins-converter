package com.example.llmagent.acceptance;

import com.example.llmagent.adapter.out.mcp.MuralMcpToolProvider;
import com.example.llmagent.application.ChatProperties;
import com.example.llmagent.application.RuntimeSettingsService;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mural MCP 執行期設定 steps。停用情境不觸發任何子程序啟動,測試可離線決定性執行。 */
public class MuralSettingsSteps {

    private RuntimeSettingsService settings;
    private MuralMcpToolProvider provider;
    private long versionBefore;
    private String secret;

    @Before
    public void setup() {
        settings = new RuntimeSettingsService(new ChatProperties("test-model", "sys"), "http://x", "k");
        provider = new MuralMcpToolProvider(settings);
        versionBefore = settings.muralVersion();
    }

    @Given("Mural MCP 初始為停用")
    public void muralDisabled() {
        assertFalse(settings.muralEnabled());
    }

    @When("於執行期啟用 Mural 並設定 Client ID {string}")
    public void enableWithClientId(String clientId) {
        settings.updateMural(true, clientId, null);
    }

    @When("以全空欄位更新 Mural 設定")
    public void updateWithBlanks() {
        settings.updateMural(null, "", "  ");
    }

    @When("於執行期設定 Mural Client Secret {string}")
    public void setSecret(String value) {
        secret = value;
        settings.updateMural(null, null, value);
    }

    @Then("Mural 參數版本應遞增")
    public void versionBumped() {
        assertTrue(settings.muralVersion() > versionBefore,
                "muralVersion 應遞增,實際 " + settings.muralVersion());
    }

    @Then("Mural 參數版本應維持不變")
    public void versionUnchanged() {
        assertEquals(versionBefore, settings.muralVersion());
    }

    @Then("Mural 設定應為啟用且 Client ID 為 {string}")
    public void muralEnabledWithId(String clientId) {
        assertTrue(settings.muralEnabled());
        assertEquals(clientId, settings.muralClientId());
    }

    @Then("遮罩後的 Mural Secret 應以 {string} 開頭且不含完整明碼")
    public void secretMasked(String prefix) {
        String masked = settings.muralClientSecretMasked();
        assertTrue(masked.startsWith(prefix), "遮罩值:" + masked);
        assertTrue(masked.contains("*"));
        assertFalse(masked.contains(secret), "遮罩值不得含完整明碼");
    }

    @Then("Mural 工具提供者應回傳 0 個工具且不啟動連線")
    public void providerReturnsEmpty() {
        assertEquals(0, provider.getToolCallbacks().length);
        assertFalse(provider.status().connected());
    }
}
