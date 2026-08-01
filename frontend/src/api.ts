import type { DoneInfo, LogLine, ModelOption, ToolCallEvent } from "./types";

export interface AgentProfile {
  id: string;
  version: number;
  name: string;
  description: string | null;
  systemPrompt: string;
  defaultModelId: string | null;
  temperature: number | null;
  enabled: boolean;
}

export async function fetchAgentProfiles(): Promise<AgentProfile[]> {
  const res = await fetch("/api/agent-profiles");
  if (!res.ok) throw new Error(`fetchAgentProfiles failed: ${res.status}`);
  return res.json();
}

export async function fetchAgentProfileVersions(id: string): Promise<AgentProfile[]> {
  const res = await fetch(`/api/agent-profiles/${id}/versions`);
  if (!res.ok) throw new Error(`versions failed: ${res.status}`);
  return res.json();
}

export async function saveAgentProfile(
  id: string | null,
  body: { name: string; description?: string; systemPrompt: string; defaultModelId?: string },
): Promise<AgentProfile> {
  const res = await fetch(id ? `/api/agent-profiles/${id}` : "/api/agent-profiles", {
    method: id ? "PUT" : "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`saveAgentProfile failed: ${res.status}`);
  return res.json();
}

export interface ServerArtifact {
  id: string;
  messageId: string;
  type: "GHERKIN" | "JAVA" | "MARKDOWN" | "DOCX";
  language: string;
  content: string;
  version: number;
}

/** 該對話中某型別產出物的全部版本(舊→新),供版本 diff(WP5-T3)。 */
export async function fetchArtifactVersions(
  conversationId: string,
  type: ServerArtifact["type"],
): Promise<ServerArtifact[]> {
  const res = await fetch(`/api/conversations/${conversationId}/artifacts?type=${type}`);
  if (!res.ok) throw new Error(`fetchArtifactVersions failed: ${res.status}`);
  return res.json();
}

export interface MuralSettings {
  enabled: boolean;
  clientId: string;
  clientSecretMasked: string;
  connected: boolean;
  toolCount: number;
  error: string | null;
}

export interface Settings {
  systemPrompt: string;
  baseUrl: string;
  apiKeyMasked: string;
  defaultModelId: string;
  mural: MuralSettings;
}

/** 取得執行期設定(token 遮罩)。 */
export async function fetchSettings(): Promise<Settings> {
  const res = await fetch("/api/settings");
  if (!res.ok) throw new Error(`fetchSettings failed: ${res.status}`);
  return res.json();
}

/** 更新執行期設定;空欄位表示維持不變。 */
export async function updateSettings(patch: {
  systemPrompt?: string;
  baseUrl?: string;
  apiKey?: string;
  mural?: { enabled?: boolean; clientId?: string; clientSecret?: string };
}): Promise<Settings> {
  const res = await fetch("/api/settings", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(`updateSettings failed: ${res.status}`);
  return res.json();
}

/** Mural MCP 連線測試:以目前參數啟動/重用連線並回報工具數。 */
export async function testMural(): Promise<{ ok: boolean; toolCount: number; error: string | null }> {
  const res = await fetch("/api/settings/mural/test", { method: "POST" });
  if (!res.ok) throw new Error(`testMural failed: ${res.status}`);
  return res.json();
}

/** 上傳檔案至 MinIO(WP6-T1),回傳 fileId 與預覽 URL。 */
export async function uploadFile(file: File): Promise<{ fileId: string; filename: string; previewUrl: string }> {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch("/api/files", { method: "POST", body: form });
  if (!res.ok) throw new Error(`uploadFile failed: ${res.status}`);
  return res.json();
}

/** 將 Markdown 文件轉為 Word(.docx)Blob(WP6-T3);後端 Apache POI 產生。
 *  templateFileId:先上傳的 Word 範本,設定時以範本套版({{title}} / {{content}} 佔位)。 */
export async function renderDocx(markdown: string, title: string, templateFileId?: string): Promise<Blob> {
  const res = await fetch("/api/docx", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ markdown, title, templateFileId }),
  });
  if (!res.ok) throw new Error(`renderDocx failed: ${res.status}`);
  return res.blob();
}

/** BRD 模板套版:以 LLM 產出的套版資料於原 Word 模板上填寫(樣式完全保留)。 */
export async function fillBrdDocx(
  values: Record<string, string>,
  scenarios?: Record<string, string>[],
  templateFileId?: string,
): Promise<Blob> {
  const res = await fetch("/api/docx/fill", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ values, scenarios, templateFileId }),
  });
  if (!res.ok) throw new Error(`fillBrdDocx failed: ${res.status}`);
  return res.blob();
}

/** 動態拉取 Provider 模型清單(WP2-T2)。後端已將 Claude 置前。 */
export async function fetchModels(providerId = "ica"): Promise<ModelOption[]> {
  const res = await fetch(`/api/providers/${providerId}/models`);
  if (!res.ok) throw new Error(`fetchModels failed: ${res.status}`);
  const data: Array<{ id: string; providerId: string }> = await res.json();
  return data.map((m) => ({ id: m.id, label: m.id }));
}

/** 建立對話,回傳 conversationId。可指定 Agent Profile(其 prompt/模型作為預設)。 */
export async function createConversation(
  modelId: string,
  agentProfileId?: string,
  promptVariables?: Record<string, string>,
): Promise<string> {
  const res = await fetch("/api/conversations", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title: "對話", modelId, agentProfileId, promptVariables }),
  });
  if (!res.ok) throw new Error(`createConversation failed: ${res.status}`);
  return (await res.json()).conversationId;
}

/** 清除對話與產出物(重新開始)。冪等,後端不存在亦回 204。 */
export async function deleteConversation(conversationId: string): Promise<void> {
  const res = await fetch(`/api/conversations/${conversationId}`, { method: "DELETE" });
  if (!res.ok) throw new Error(`deleteConversation failed: ${res.status}`);
}

/** 送出使用者訊息,回傳 messageId。modelId / agentProfileId 為對話中切換(WP3-T3);fileIds 為對話附件。 */
export async function postMessage(
  conversationId: string,
  content: string,
  modelId?: string,
  agentProfileId?: string,
  promptVariables?: Record<string, string>,
  fileIds?: string[],
): Promise<string> {
  const res = await fetch(`/api/conversations/${conversationId}/messages`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ content, modelId, agentProfileId, promptVariables, fileIds }),
  });
  if (!res.ok) throw new Error(`postMessage failed: ${res.status}`);
  return (await res.json()).messageId;
}

export interface ProviderInfo {
  id: string;
  type: "OLLAMA" | "OPENAI_COMPATIBLE" | "ANTHROPIC";
  baseUrl: string;
  apiKeyRef: string | null;
  enabled: boolean;
}

export async function fetchProviders(): Promise<ProviderInfo[]> {
  const res = await fetch("/api/providers");
  if (!res.ok) throw new Error(`fetchProviders failed: ${res.status}`);
  return res.json();
}

export async function registerProvider(p: {
  id: string;
  type: string;
  baseUrl: string;
  apiKeyRef?: string;
}): Promise<ProviderInfo> {
  const res = await fetch("/api/providers", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(p),
  });
  if (!res.ok) throw new Error(`registerProvider failed: ${res.status}`);
  return res.json();
}

export interface ProviderTestResult {
  ok: boolean;
  latencyMs: number;
  models: number;
  error: string | null;
}

export async function testProvider(id: string): Promise<ProviderTestResult> {
  const res = await fetch(`/api/providers/${id}/test`, { method: "POST" });
  if (!res.ok) throw new Error(`testProvider failed: ${res.status}`);
  return res.json();
}

export interface StreamHandlers {
  onThinking: (delta: string) => void;
  onContent: (delta: string) => void;
  onToolCall: (ev: ToolCallEvent) => void;
  onLog: (line: LogLine) => void;
  onDone: (info: DoneInfo) => void;
  onError: (err: unknown) => void;
}

/** 開啟 SSE 串流。回傳 EventSource,呼叫端可於卸載時 close()。 */
export function streamMessage(messageId: string, h: StreamHandlers): EventSource {
  const es = new EventSource(`/api/messages/${messageId}/stream`);
  es.addEventListener("thinking", (e) => h.onThinking(JSON.parse((e as MessageEvent).data).delta));
  es.addEventListener("content", (e) => h.onContent(JSON.parse((e as MessageEvent).data).delta));
  es.addEventListener("tool_call", (e) => h.onToolCall(JSON.parse((e as MessageEvent).data) as ToolCallEvent));
  es.addEventListener("log", (e) => h.onLog(JSON.parse((e as MessageEvent).data) as LogLine));
  es.addEventListener("done", (e) => {
    h.onDone(JSON.parse((e as MessageEvent).data) as DoneInfo);
    es.close();
  });
  es.onerror = (e) => {
    // done 事件後由伺服器關閉連線,EventSource 會觸發 error;僅在仍開啟時視為錯誤。
    if (es.readyState !== EventSource.CLOSED) {
      h.onError(e);
    }
    es.close();
  };
  return es;
}
