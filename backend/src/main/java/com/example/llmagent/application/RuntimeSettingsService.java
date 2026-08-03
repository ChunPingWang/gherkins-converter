package com.example.llmagent.application;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 執行期可調設定(System Prompt / LLM API base URL / API Token / 預設模型)。
 *
 * <p>初始值來自環境變數(application.yaml),之後可經 {@code PUT /api/settings} 於執行期修改;
 * 僅存於記憶體,重啟即還原為環境值 —— 金鑰不落地,符合 CLAUDE.md #8。
 * {@link #version()} 遞增供 adapter 判斷是否需重建連線。
 */
@Service
public class RuntimeSettingsService {

    private final AtomicLong version = new AtomicLong(1);
    private final AtomicLong muralVersion = new AtomicLong(1);

    private volatile String systemPrompt;
    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile String defaultModelId;
    private volatile boolean muralEnabled;
    private volatile String muralClientId;
    private volatile String muralClientSecret;
    private volatile String gitRepoUrl;
    private volatile String gitToken;

    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeSettingsService(ChatProperties chatProps,
                                  @Value("${spring.ai.openai.base-url}") String baseUrl,
                                  @Value("${spring.ai.openai.api-key}") String apiKey,
                                  @Value("${llmagent.mcp.mural.enabled:false}") boolean muralEnabled,
                                  @Value("${llmagent.mcp.mural.client-id:}") String muralClientId,
                                  @Value("${llmagent.mcp.mural.client-secret:}") String muralClientSecret,
                                  @Value("${llmagent.git.repo-url:}") String gitRepoUrl,
                                  @Value("${llmagent.git.token:}") String gitToken) {
        this.systemPrompt = chatProps.defaultSystemPrompt();
        this.defaultModelId = chatProps.defaultModelId();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.muralEnabled = muralEnabled;
        this.muralClientId = muralClientId;
        this.muralClientSecret = muralClientSecret;
        this.gitRepoUrl = gitRepoUrl;
        this.gitToken = gitToken;
    }

    /** 測試用簡便建構子(Mural MCP 停用、Git 未設定)。 */
    public RuntimeSettingsService(ChatProperties chatProps, String baseUrl, String apiKey) {
        this(chatProps, baseUrl, apiKey, false, "", "", "", "");
    }

    public String gitRepoUrl() {
        return gitRepoUrl;
    }

    public String gitToken() {
        return gitToken;
    }

    public String gitTokenMasked() {
        return mask(gitToken);
    }

    /** 更新 Git 整合參數;blank 表示維持不變(僅存記憶體,金鑰不落地)。 */
    public synchronized void updateGit(String repoUrl, String token) {
        if (repoUrl != null && !repoUrl.isBlank()) {
            this.gitRepoUrl = repoUrl.strip();
        }
        if (token != null && !token.isBlank()) {
            this.gitToken = token.strip();
        }
    }

    public long version() {
        return version.get();
    }

    /** Mural MCP 參數版本;變更時 bump,通知工具 adapter 重連(與 LLM 連線版本各自獨立)。 */
    public long muralVersion() {
        return muralVersion.get();
    }

    public boolean muralEnabled() {
        return muralEnabled;
    }

    public String muralClientId() {
        return muralClientId;
    }

    public String muralClientSecret() {
        return muralClientSecret;
    }

    public String muralClientSecretMasked() {
        return mask(muralClientSecret);
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String defaultModelId() {
        return defaultModelId;
    }

    /** 遮罩後的 token(僅顯示前 4 碼),供設定畫面呈現。 */
    public String apiKeyMasked() {
        return mask(apiKey);
    }

    private static String mask(String k) {
        if (k == null || k.isBlank()) {
            return "";
        }
        if (k.length() <= 4) {
            return "****";
        }
        return k.substring(0, 4) + "*".repeat(Math.min(k.length() - 4, 20));
    }

    /**
     * 更新設定;null/blank 欄位表示「維持不變」。
     * 連線相關(baseUrl / apiKey)有變更時 bump version,通知 adapter 重建。
     */
    public synchronized void update(String systemPrompt, String baseUrl, String apiKey) {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            this.systemPrompt = systemPrompt;
        }
        boolean connectionChanged = false;
        if (baseUrl != null && !baseUrl.isBlank() && !baseUrl.equals(this.baseUrl)) {
            this.baseUrl = baseUrl.strip();
            connectionChanged = true;
        }
        if (apiKey != null && !apiKey.isBlank() && !apiKey.equals(this.apiKey)) {
            this.apiKey = apiKey.strip();
            connectionChanged = true;
        }
        if (connectionChanged) {
            version.incrementAndGet();
        }
    }

    /**
     * 更新 Mural MCP 參數;null(enabled)/blank(id、secret)表示「維持不變」。
     * 任一項有效變更即 bump muralVersion,工具 adapter 於下次使用時以新參數重連。
     */
    public synchronized void updateMural(Boolean enabled, String clientId, String clientSecret) {
        boolean changed = false;
        if (enabled != null && enabled != this.muralEnabled) {
            this.muralEnabled = enabled;
            changed = true;
        }
        if (clientId != null && !clientId.isBlank() && !clientId.strip().equals(this.muralClientId)) {
            this.muralClientId = clientId.strip();
            changed = true;
        }
        if (clientSecret != null && !clientSecret.isBlank() && !clientSecret.strip().equals(this.muralClientSecret)) {
            this.muralClientSecret = clientSecret.strip();
            changed = true;
        }
        if (changed) {
            muralVersion.incrementAndGet();
        }
    }
}
