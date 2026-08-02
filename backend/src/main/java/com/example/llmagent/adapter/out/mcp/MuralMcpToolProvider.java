package com.example.llmagent.adapter.out.mcp;

import java.time.Duration;
import java.util.Map;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import com.example.llmagent.application.RuntimeSettingsService;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;

/**
 * Mural MCP 工具提供者:以 stdio 啟動 mural-mcp 子程序,將其工具轉為 Spring AI
 * {@link ToolCallback}。參數(啟用/Client ID/Secret)來自 {@link RuntimeSettingsService},
 * 可於 UI 執行期修改 —— muralVersion 變更時延遲重連(關舊建新),與 LLM 連線同套版本比對模式。
 *
 * <p>注意:{@link #getToolCallbacks()} 內含 blocking I/O(啟動子程序、listTools),
 * 呼叫端必須在 boundedElastic 等可阻塞執行緒上使用(SpringAiChatModelAdapter 已如此)。
 */
@Component
public class MuralMcpToolProvider implements ToolCallbackProvider {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MuralMcpToolProvider.class);
    private static final ToolCallback[] NONE = new ToolCallback[0];

    private final RuntimeSettingsService settings;

    private volatile McpSyncClient client;
    private volatile long builtVersion = -1;
    private volatile int lastToolCount;
    private volatile String lastError;

    public MuralMcpToolProvider(RuntimeSettingsService settings) {
        this.settings = settings;
    }

    /** 供設定畫面呈現的目前狀態(不做 blocking 呼叫,回報快取值)。 */
    public record Status(boolean enabled, boolean connected, int toolCount, String error) {
    }

    public Status status() {
        return new Status(settings.muralEnabled(), client != null, lastToolCount, lastError);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        if (!settings.muralEnabled() || settings.muralClientId().isBlank()) {
            closeIfOpen();
            return NONE;
        }
        try {
            McpSyncClient c = ensureClient();
            ToolCallback[] callbacks = new SyncMcpToolCallbackProvider(c).getToolCallbacks();
            lastToolCount = callbacks.length;
            lastError = null;
            return callbacks;
        } catch (RuntimeException e) {
            // 工具不可用時降級為「無工具」,不阻斷對話;錯誤留給設定畫面呈現
            lastError = e.getMessage();
            log.warn("Mural MCP 工具取得失敗,本次對話不掛工具:{}", e.getMessage());
            closeIfOpen();
            return NONE;
        }
    }

    private McpSyncClient ensureClient() {
        long v = settings.muralVersion();
        McpSyncClient c = client;
        if (c == null || builtVersion != v) {
            synchronized (this) {
                if (client == null || builtVersion != settings.muralVersion()) {
                    closeIfOpen();
                    ServerParameters params = ServerParameters.builder("npx")
                            .args("-y", "github:anjanpoonacha/mural-mcp")
                            .env(Map.of(
                                    "MURAL_CLIENT_ID", settings.muralClientId(),
                                    "MURAL_CLIENT_SECRET", settings.muralClientSecret()))
                            .build();
                    // client 名稱會成為工具名前綴(mural_get_widgets),
                    // Agent Profile tools 白名單以「mural」子字串比對,前綴不可省
                    McpSyncClient created = McpClient.sync(new StdioClientTransport(params))
                            .clientInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("mural", "1.0.0"))
                            .requestTimeout(Duration.ofSeconds(60))
                            .build();
                    created.initialize();
                    client = created;
                    builtVersion = settings.muralVersion();
                }
                c = client;
            }
        }
        return c;
    }

    private void closeIfOpen() {
        McpSyncClient old = client;
        if (old != null) {
            client = null;
            try {
                old.closeGracefully();
            } catch (RuntimeException e) {
                log.debug("關閉舊 Mural MCP client 失敗(忽略):{}", e.getMessage());
            }
        }
    }
}
