import { useEffect, useState } from "react";
import { fetchSettings, testMural, updateSettings, type Settings } from "../api";
import { Modal } from "./Modal";

/**
 * 設定視窗:System Prompt、LLM API 連線(base URL / token)與 Mural MCP 參數。
 * 金鑰欄留空表示維持不變;儲存後僅存於後端記憶體,重啟還原為環境變數值。
 */
export function SettingsModal({ onClose }: { onClose: () => void }) {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [systemPrompt, setSystemPrompt] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [muralEnabled, setMuralEnabled] = useState(false);
  const [muralClientId, setMuralClientId] = useState("");
  const [muralSecret, setMuralSecret] = useState("");
  const [muralTest, setMuralTest] = useState<string | null>(null);
  const [status, setStatus] = useState<"idle" | "saving" | "saved" | "error">("idle");

  useEffect(() => {
    fetchSettings()
      .then((s) => {
        setSettings(s);
        setSystemPrompt(s.systemPrompt);
        setBaseUrl(s.baseUrl);
        setMuralEnabled(s.mural.enabled);
        setMuralClientId(s.mural.clientId);
      })
      .catch(() => setStatus("error"));
  }, []);

  async function save() {
    setStatus("saving");
    try {
      const s = await updateSettings({
        systemPrompt,
        baseUrl,
        ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}),
        mural: {
          enabled: muralEnabled,
          ...(muralClientId.trim() ? { clientId: muralClientId.trim() } : {}),
          ...(muralSecret.trim() ? { clientSecret: muralSecret.trim() } : {}),
        },
      });
      setSettings(s);
      setSystemPrompt(s.systemPrompt);
      setBaseUrl(s.baseUrl);
      setApiKey("");
      setMuralEnabled(s.mural.enabled);
      setMuralClientId(s.mural.clientId);
      setMuralSecret("");
      setStatus("saved");
      setTimeout(() => setStatus("idle"), 2000);
    } catch {
      setStatus("error");
    }
  }

  async function runMuralTest() {
    setMuralTest("測試中…");
    try {
      const r = await testMural();
      setMuralTest(r.ok ? `✓ 連線成功,共 ${r.toolCount} 個工具` : `✗ ${r.error ?? "連線失敗"}`);
    } catch {
      setMuralTest("✗ 測試請求失敗");
    }
  }

  return (
    <Modal
      title="設定"
      onClose={onClose}
      actions={
        <button onClick={save} disabled={status === "saving" || !settings}>
          {status === "saving" ? "儲存中…" : status === "saved" ? "已儲存 ✓" : "儲存"}
        </button>
      }
    >
      <div className="settings-form">
        {!settings && status !== "error" && <p className="empty">載入設定中…</p>}
        {status === "error" && <p className="empty">設定載入或儲存失敗,請稍後再試。</p>}
        {settings && (
          <>
            <section>
              <h3>System Prompt</h3>
              <p className="hint">
                控制 Agent 的行為與產出規範(BDD / DDD / SOLID、範圍限制等)。修改後立即生效於新對話。
              </p>
              <textarea
                className="settings-prompt"
                value={systemPrompt}
                onChange={(e) => setSystemPrompt(e.target.value)}
                rows={16}
                spellCheck={false}
              />
            </section>

            <section>
              <h3>LLM API 連線</h3>
              <p className="hint">
                OpenAI-Compatible Gateway(如 IBM ICA)。修改後下一次呼叫即用新連線;
                設定僅存於伺服器記憶體,重啟還原為環境變數值。
              </p>
              <label className="field">
                <span>API Base URL</span>
                <input
                  type="url"
                  value={baseUrl}
                  onChange={(e) => setBaseUrl(e.target.value)}
                  placeholder="https://api.nextgen-beta.ica.ibm.com/ica"
                  spellCheck={false}
                />
              </label>
              <label className="field">
                <span>API Token(目前:{settings.apiKeyMasked};留空 = 不變更)</span>
                <input
                  type="password"
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  placeholder="輸入新 token 以更換"
                  autoComplete="off"
                />
              </label>
              <p className="hint">預設模型:{settings.defaultModelId}</p>
            </section>

            <section>
              <h3>Mural MCP(看板讀取)</h3>
              <p className="hint">
                Agent 透過 MCP 工具讀取 Mural 白板(需 Agent Profile 的 tools 含「mural」)。
                參數僅存伺服器記憶體,重啟還原為環境變數;變更後下一次工具呼叫自動重連。
              </p>
              <label className="field checkbox-field">
                <input
                  type="checkbox"
                  checked={muralEnabled}
                  onChange={(e) => setMuralEnabled(e.target.checked)}
                />
                <span>啟用 Mural MCP</span>
              </label>
              <label className="field">
                <span>Client ID</span>
                <input
                  type="text"
                  value={muralClientId}
                  onChange={(e) => setMuralClientId(e.target.value)}
                  placeholder="Mural app 的 Client ID"
                  spellCheck={false}
                />
              </label>
              <label className="field">
                <span>
                  Client Secret(目前:{settings.mural.clientSecretMasked || "未設定"};留空 = 不變更)
                </span>
                <input
                  type="password"
                  value={muralSecret}
                  onChange={(e) => setMuralSecret(e.target.value)}
                  placeholder="輸入新 secret 以更換"
                  autoComplete="off"
                />
              </label>
              <p className="hint">
                狀態:{settings.mural.connected
                  ? `已連線(${settings.mural.toolCount} 個工具)`
                  : settings.mural.error
                    ? `未連線 — ${settings.mural.error}`
                    : "未連線(首次工具呼叫或連線測試時建立)"}
                ;OAuth 授權為一次性,token 在伺服器 ~/.mural-mcp/tokens.json
              </p>
              <div className="field">
                <button type="button" onClick={runMuralTest} disabled={!muralEnabled}>
                  連線測試(以已儲存的參數)
                </button>
                {muralTest && <p className="hint">{muralTest}</p>}
              </div>
            </section>
          </>
        )}
      </div>
    </Modal>
  );
}
