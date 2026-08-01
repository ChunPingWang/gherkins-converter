package com.example.llmagent.adapter.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.llmagent.adapter.out.mcp.MuralMcpToolProvider;
import com.example.llmagent.application.RuntimeSettingsService;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 執行期設定端點:System Prompt、LLM API 連線(base URL / token)與 Mural MCP 參數。
 *
 * <p>GET 回傳目前值(金鑰遮罩);PUT 更新,空欄位表示維持不變。
 * 設定僅存記憶體,重啟還原為環境變數值(金鑰不落地)。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final RuntimeSettingsService settings;
    private final MuralMcpToolProvider muralTools;

    public SettingsController(RuntimeSettingsService settings, MuralMcpToolProvider muralTools) {
        this.settings = settings;
        this.muralTools = muralTools;
    }

    public record MuralView(boolean enabled, String clientId, String clientSecretMasked,
                            boolean connected, int toolCount, String error) {
    }

    public record SettingsView(String systemPrompt, String baseUrl, String apiKeyMasked,
                               String defaultModelId, MuralView mural) {
    }

    public record MuralUpdate(Boolean enabled, String clientId, String clientSecret) {
    }

    public record SettingsUpdate(String systemPrompt, String baseUrl, String apiKey, MuralUpdate mural) {
    }

    public record MuralTestResult(boolean ok, int toolCount, String error) {
    }

    @GetMapping
    public SettingsView get() {
        MuralMcpToolProvider.Status st = muralTools.status();
        return new SettingsView(
                settings.systemPrompt(), settings.baseUrl(),
                settings.apiKeyMasked(), settings.defaultModelId(),
                new MuralView(settings.muralEnabled(), settings.muralClientId(),
                        settings.muralClientSecretMasked(), st.connected(), st.toolCount(), st.error()));
    }

    @PutMapping
    public SettingsView update(@RequestBody SettingsUpdate req) {
        settings.update(req.systemPrompt(), req.baseUrl(), req.apiKey());
        if (req.mural() != null) {
            settings.updateMural(req.mural().enabled(), req.mural().clientId(), req.mural().clientSecret());
        }
        return get();
    }

    /** Mural MCP 連線測試:啟動/重用 client 並列舉工具。blocking I/O,移至 boundedElastic。 */
    @PostMapping("/mural/test")
    public Mono<MuralTestResult> testMural() {
        return Mono.fromCallable(() -> {
            if (!settings.muralEnabled()) {
                return new MuralTestResult(false, 0, "Mural MCP 未啟用");
            }
            int count = muralTools.getToolCallbacks().length;
            MuralMcpToolProvider.Status st = muralTools.status();
            return count > 0
                    ? new MuralTestResult(true, count, null)
                    : new MuralTestResult(false, 0, st.error() == null ? "無可用工具" : st.error());
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
