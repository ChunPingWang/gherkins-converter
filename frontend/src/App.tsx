import { useEffect, useMemo, useRef, useState } from "react";
import {
  createConversation,
  deleteConversation,
  fetchAgentProfiles,
  fetchModels,
  orchestrate,
  postMessage,
  publishConversation,
  routeAgent,
  streamMessage,
  uploadFile,
  type AgentProfile,
} from "./api";
import { useAutoScroll } from "./lib/useAutoScroll";
import { extractBrdFill } from "./lib/brdFill";
import { suggestAgent, type AgentSuggestion } from "./lib/agentSuggest";
import { AgentProfilesModal } from "./components/AgentProfilesModal";
import { ProvidersModal } from "./components/ProvidersModal";
import { MessageView } from "./components/MessageView";
import { ArtifactPanel } from "./components/ArtifactPanel";
import { LogPanel } from "./components/LogPanel";
import { WordPreview } from "./components/WordPreview";
import { Modal } from "./components/Modal";
import { Markdown } from "./components/Markdown";
import { SettingsModal } from "./components/SettingsModal";
import type { ChatMessage, LogLine, ModelOption } from "./types";

type Tab = "spec" | "word" | "plan" | "code" | "review" | "logs";

/** SDLC 流水線步驟 → 階段 Tab(部分對齊 Spec-Kit:Specify/Plan/Implement/Analyze)。 */
const STEP_TAB: Record<number, Tab> = { 1: "spec", 2: "word", 3: "plan", 4: "code", 5: "review" };

/** Agent 名稱 → 流水線步驟(單步路由時同步階段 Tab 用)。 */
const AGENT_STEP: Record<string, number> = {
  "BDD 規格 Agent": 1,
  "BRD 業務文件 Agent": 2,
  "DDD 設計 Agent": 3,
  "Java 產碼 Agent": 4,
  "Code Review Agent": 5,
};

/** 由文件內容取標題:第一個 Markdown 標題,否則預設。 */
function docTitle(markdown: string): string {
  const m = markdown.match(/^#{1,3}\s+(.+)$/m);
  return m ? m[1].trim() : "業務需求文件";
}

/** 產出物浮動視窗標題:依內容為 Gherkin 或 Java 程式碼。 */
function artifactModalTitle(markdown: string): string {
  if (/```java/i.test(markdown)) return "產出程式碼(Java / Cucumber)";
  if (/```gherkin/i.test(markdown)) return "Gherkin 情境(.feature)";
  return "產出物內容";
}

// 後端 /api/providers/ica/models 不可用時的後備清單。
const FALLBACK_MODELS: ModelOption[] = [
  { id: "claude-opus-4-8", label: "claude-opus-4-8" },
  { id: "claude-sonnet-5", label: "claude-sonnet-5" },
];
const PREFERRED_DEFAULT = "claude-opus-4-8";

let uid = 0;
const nextId = () => `local-${uid++}`;

/** Agent 下拉的「自動路由」選項值(非真實 Profile id)。 */
const AUTO_PROFILE = "__auto__";
/** 自動路由信心門檻:低於此值退回人工確認(沿用攔截確認視窗)。 */
const ROUTE_CONFIDENCE_THRESHOLD = 0.6;

/** 工具參數摘要:單行、截斷,供日誌列顯示。 */
function summarizeArgs(args: unknown): string {
  if (args == null) return "";
  const s = typeof args === "string" ? args : JSON.stringify(args);
  return s.length > 80 ? `${s.slice(0, 80)}…` : s;
}

export function App() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [logs, setLogs] = useState<LogLine[]>([]);
  const [input, setInput] = useState("");
  const [models, setModels] = useState<ModelOption[]>(FALLBACK_MODELS);
  // 模型與 Agent 選擇持久化:重新整理頁面不重置(曾因重置默默用錯 Agent)
  const [model, setModel] = useState(() => localStorage.getItem("llmagent.model") ?? PREFERRED_DEFAULT);
  const [sending, setSending] = useState(false);
  const [tab, setTab] = useState<Tab>("spec");
  // SDLC 階段狀態:currentStage 供徽章顯示;ref 供串流 handler 內分桶(避免閉包吃到舊值)
  const [currentStage, setCurrentStage] = useState(0);
  const stageRef = useRef(0);
  // 計畫(Plan)/分析(Analyze)為純 Markdown 內容,依步驟事件分桶累積
  const [stageText, setStageText] = useState<{ plan: string; review: string }>({ plan: "", review: "" });

  /** 切換目前 SDLC 階段並跳到對應 Tab;stage 3/5 起始時清空該桶。 */
  function enterStage(stage: number) {
    stageRef.current = stage;
    setCurrentStage(stage);
    const t = STEP_TAB[stage];
    if (t) setTab(t);
    if (stage === 3) setStageText((p) => ({ ...p, plan: "" }));
    if (stage === 5) setStageText((p) => ({ ...p, review: "" }));
  }
  const [modal, setModal] = useState<null | "word" | "code" | "settings" | "profiles" | "providers">(null);
  const [profiles, setProfiles] = useState<AgentProfile[]>([]);
  // Agent 一律自動路由(層次一),不提供手動切換;特定流程(如產生 BRD)以 profileOverride 指定
  const profileId = AUTO_PROFILE;
  const [uploadedDoc, setUploadedDoc] = useState<{ url: string; name: string } | null>(null);
  const [attachments, setAttachments] = useState<{ fileId: string; filename: string }[]>([]);
  const [wordTemplate, setWordTemplate] = useState<{ fileId: string; filename: string } | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const attachInputRef = useRef<HTMLInputElement>(null);
  const templateInputRef = useRef<HTMLInputElement>(null);
  const convId = useRef<string | null>(null);
  const convKey = useRef<string>(""); // model+profile;變更時開新對話
  const esRef = useRef<EventSource | null>(null);

  // Agent Profile 清單(WP2-T6):供路由後名稱顯示與 BRD 一鍵流程查找
  useEffect(() => {
    fetchAgentProfiles()
      .then(setProfiles)
      .catch(() => {});
  }, [modal]); // 管理視窗關閉後重新載入

  // 選擇持久化(localStorage)
  useEffect(() => {
    localStorage.setItem("llmagent.model", model);
  }, [model]);

  // 開啟時動態拉取 ICA 模型清單(WP2-T2);失敗則沿用後備清單。
  useEffect(() => {
    fetchModels("ica")
      .then((list) => {
        if (list.length === 0) return;
        setModels(list);
        setModel((cur) =>
          list.some((m) => m.id === cur)
            ? cur
            : (list.find((m) => m.id === PREFERRED_DEFAULT)?.id ?? list[0].id),
        );
      })
      .catch(() => {
        /* 保留後備清單 */
      });
  }, []);

  const lastAssistant = useMemo(
    () => [...messages].reverse().find((m) => m.role === "assistant"),
    [messages],
  );

  // 串流時自動捲至底部:對話區與側欄(產出物 / 日誌)各自跟隨(使用者上捲即暫停)
  const messagesRef = useAutoScroll<HTMLDivElement>([messages]);
  const tabBodyRef = useAutoScroll<HTMLDivElement>([lastAssistant?.content, logs.length, tab]);

  // BRD 套版資料(WP:BRD 套版):助理輸出 brdFill JSON 時,Word 預覽改走原模板填寫
  const brdFill = useMemo(
    () => (lastAssistant && !lastAssistant.streaming ? extractBrdFill(lastAssistant.content) : null),
    [lastAssistant],
  );

  // 發布產出至 Git(GitFlow:場景開 Issue → 分支提交 → PR 連結 Issues)
  const [publishing, setPublishing] = useState(false);
  async function publishToGit() {
    if (!convId.current) return;
    setPublishing(true);
    const pushLog = (line: LogLine) => setLogs((prev) => [...prev, line]);
    try {
      const r = await publishConversation(convId.current);
      pushLog({
        level: "INFO", source: "🚀 發布",
        msg: `已開 ${r.issues.length} 張 Issue、提交 ${r.fileCount} 個檔案至 ${r.branch},PR:${r.prUrl}`,
        ts: new Date().toISOString(),
      });
      r.issues.forEach((i) => pushLog({
        level: "INFO", source: "🚀 發布", msg: `Issue #${i.number}:${i.url}`, ts: new Date().toISOString(),
      }));
      setTab("logs");
    } catch (err) {
      pushLog({
        level: "ERROR", source: "🚀 發布",
        msg: `發布失敗:${err instanceof Error ? err.message : String(err)}(請確認 ⚙ 設定的 Git Repo/Token)`,
        ts: new Date().toISOString(),
      });
      setTab("logs");
    } finally {
      setPublishing(false);
    }
  }

  // 一鍵步驟二:上則回覆含 Gherkin 時,「📄 產生 BRD」自動改用 BRD Agent 送出
  const brdProfile = useMemo(
    () => profiles.find((p) => p.name === "BRD 業務文件 Agent"),
    [profiles],
  );
  const javaBlockCount = useMemo(
    () => (lastAssistant?.content.match(/```java[\s\S]*?```/g) ?? []).length,
    [lastAssistant],
  );
  const gherkinBlockCount = useMemo(
    () => (lastAssistant?.content.match(/```gherkin[\s\S]*?```/g) ?? []).length,
    [lastAssistant],
  );
  const gherkinBlocks = useMemo(
    () =>
      lastAssistant && !lastAssistant.streaming
        ? lastAssistant.content.match(/```gherkin[\s\S]*?```/g)
        : null,
    [lastAssistant],
  );

  function sendBrd() {
    if (!brdProfile || !gherkinBlocks?.length) return;
    const text =
      "請依據以下 Gherkin 產出 BRD 套版資料:\n\n" + gherkinBlocks.join("\n\n");
    send(text, brdProfile.id);
  }

  // 路由信心不足時的確認視窗:採用建議 Agent 或以全域預設送出
  const [pendingConfirm, setPendingConfirm] = useState<
    { text: string; suggestion: AgentSuggestion } | null
  >(null);

  /** 確認後實際送出:useSuggested 決定用建議 Agent 或全域預設。 */
  function confirmSend(useSuggested: boolean) {
    const pc = pendingConfirm;
    if (!pc) return;
    setPendingConfirm(null);
    setInput("");
    if (useSuggested) {
      send(pc.text, pc.suggestion.profile.id);
    } else {
      send(pc.text, undefined, true);
    }
  }
  const wordTitle =
    brdFill?.values?.DOC_TITLE ?? brdFill?.values?.PROJECT_NAME ??
    (uploadedDoc?.name ?? docTitle(lastAssistant?.content ?? ""));

  function patch(id: string, fn: (m: ChatMessage) => ChatMessage) {
    setMessages((prev) => prev.map((m) => (m.id === id ? fn(m) : m)));
  }

  /**
   * 送出訊息。textArg / profileOverride 供一鍵流程(如「產生 BRD」)指定內容與 Agent;
   * skipIntercept 供攔截確認後的實際送出。
   */
  async function send(textArg?: string, profileOverride?: string, skipIntercept = false) {
    const text = (textArg ?? input).trim();
    if (!text || sending) return;
    const isAuto = (profileOverride ?? profileId) === AUTO_PROFILE;
    // 自動路由(層次一):送出前先取得決策物件;低信心或無法判斷退回人工確認(沿用攔截視窗)
    let effProfile = profileOverride ?? profileId;
    let routeLog: LogLine | null = null;
    let pipeline = false; // 層次二:PIPELINE 決策 → SDLC 流水線串流
    if (isAuto) {
      if (skipIntercept) {
        effProfile = ""; // 確認視窗選「仍用目前」:自動模式下以全域預設送出
      } else {
        setSending(true);
        let decision: Awaited<ReturnType<typeof routeAgent>> | null = null;
        try {
          decision = await routeAgent(text);
        } catch {
          decision = null;
        }
        setSending(false);
        const routed = decision && decision.target === "AGENT" && decision.agentProfileId
          ? profiles.find((p) => p.id === decision.agentProfileId) ?? null
          : null;
        if (decision && decision.target === "PIPELINE") {
          pipeline = true;
          effProfile = ""; // 對話以全域預設建立;各步驟訊息由 Orchestrator 切換 Agent
          setStageText({ plan: "", review: "" }); // 新一輪流水線:清空階段桶
          enterStage(1);
          routeLog = {
            level: "INFO", source: "🤖 路由",
            msg: `啟動 SDLC 全流程(信心 ${(decision.confidence * 100).toFixed(0)}%)— ${decision.reason}`,
            ts: new Date().toISOString(),
          };
        } else if (routed && decision && decision.confidence >= ROUTE_CONFIDENCE_THRESHOLD) {
          effProfile = routed.id;
          const stage = AGENT_STEP[routed.name];
          if (stage) enterStage(stage); // 單步路由:同步切到對應階段 Tab
          routeLog = {
            level: "INFO", source: "🤖 路由",
            msg: `自動選擇「${routed.name}」(信心 ${(decision.confidence * 100).toFixed(0)}%)— ${decision.reason}`,
            ts: new Date().toISOString(),
          };
        } else if (routed && decision) {
          setPendingConfirm({
            text,
            suggestion: {
              profile: routed,
              reason: `自動路由建議(信心 ${(decision.confidence * 100).toFixed(0)}%):${decision.reason}`,
            },
          });
          return;
        } else {
          const s = suggestAgent(text, attachments.map((a) => a.filename), profiles, "");
          if (s) {
            setPendingConfirm({ text, suggestion: s });
            return;
          }
          effProfile = "";
          routeLog = {
            level: "WARN", source: "🤖 路由",
            msg: `無法判斷意圖(${decision?.reason ?? "路由呼叫失敗"}),以全域預設送出`,
            ts: new Date().toISOString(),
          };
        }
      }
    }
    // 一鍵流程(如產生 BRD)以 profileOverride 指定 Agent:同步對應階段 Tab
    if (!pipeline && profileOverride) {
      const name = profiles.find((p) => p.id === profileOverride)?.name;
      const st = name ? AGENT_STEP[name] : undefined;
      if (st) enterStage(st);
    }
    setSending(true);
    if (textArg === undefined) setInput("");

    try {
      const vars = { project_name: "llm-webapp", gherkin_locale: "zh-TW" };
      if (!convId.current) {
        convId.current = await createConversation(model, effProfile || undefined, vars);
        convKey.current = `${model}|${effProfile}`;
      }
      // 對話中切換模型 / Agent(WP3-T3):同一對話,覆寫隨訊息送出
      const key = `${model}|${effProfile}`;
      const switched = convKey.current !== key;
      convKey.current = key;
      const attached = attachments;
      const display = attached.length === 0
        ? text
        : `${text}\n\n${attached.map((a) => `📎 ${a.filename}`).join("\n")}`;
      const userMsg: ChatMessage = {
        id: nextId(), role: "user", content: display, thinking: "", logs: [], streaming: false,
      };
      const assistantId = nextId();
      const assistant: ChatMessage = {
        id: assistantId, role: "assistant", content: "", thinking: "",
        logs: routeLog ? [routeLog] : [],
        streaming: true, model,
      };
      setMessages((prev) => [...prev, userMsg, assistant]);
      if (routeLog) {
        const line = routeLog;
        setLogs((prev) => [...prev, line]);
      }

      const messageId = pipeline
        ? (await orchestrate(convId.current, text, vars)).messageId
        : await postMessage(
            convId.current,
            text,
            switched ? model : undefined,
            switched && effProfile ? effProfile : undefined,
            switched && effProfile ? vars : undefined,
            attached.length > 0 ? attached.map((a) => a.fileId) : undefined,
          );
      setAttachments([]);

      esRef.current = streamMessage(messageId, {
        onThinking: (d) => patch(assistantId, (m) => ({ ...m, thinking: m.thinking + d })),
        onContent: (d) => {
          patch(assistantId, (m) => ({ ...m, content: m.content + d }));
          // 計畫/分析階段的內容同步累積到階段桶(其餘階段由 artifact 型別過濾呈現)
          if (stageRef.current === 3) setStageText((p) => ({ ...p, plan: p.plan + d }));
          else if (stageRef.current === 5) setStageText((p) => ({ ...p, review: p.review + d }));
        },
        onToolCall: (ev) => {
          const line: LogLine = {
            level: ev.status === "error" ? "ERROR" : "INFO",
            source: `🔧 ${ev.name}`,
            msg: ev.status === "started"
              ? `呼叫開始 ${summarizeArgs(ev.arguments)}`
              : ev.status === "finished" ? "執行完成" : "執行失敗",
            ts: new Date().toISOString(),
          };
          setLogs((prev) => [...prev, line]);
          patch(assistantId, (m) => ({ ...m, logs: [...m.logs, line] }));
        },
        onLog: (line) => {
          if (line.source === "tool") return; // tool_call 事件已以 🔧 呈現,避免重複
          // 流水線步驟邊界:「步驟 i/n 開始」→ 切換階段桶與 Tab
          if (line.source === "orchestrator") {
            const m = line.msg.match(/^步驟 (\d+)\/\d+ 開始/);
            if (m) enterStage(Number(m[1]));
          }
          setLogs((prev) => [...prev, line]);
          patch(assistantId, (m) => ({ ...m, logs: [...m.logs, line] }));
        },
        onDone: (info) => {
          patch(assistantId, (m) => ({ ...m, done: info, streaming: false }));
          stageRef.current = 0;
          setCurrentStage(0);
          setSending(false);
        },
        onError: () => {
          patch(assistantId, (m) => ({ ...m, streaming: false }));
          setLogs((prev) => [...prev, { level: "ERROR", source: "client", msg: "串流連線中斷", ts: "" }]);
          setSending(false);
        },
      }, pipeline ? `/api/messages/${messageId}/orchestrate/stream` : undefined);
    } catch (err) {
      setLogs((prev) => [...prev, { level: "ERROR", source: "client", msg: String(err), ts: "" }]);
      setSending(false);
    }
  }

  /** 清除對話與產出物(前端狀態 + 後端資料),重新開始。 */
  async function clearConversation() {
    if (!window.confirm("確定要清除對話與產出物?此動作無法復原。")) return;
    esRef.current?.close();
    esRef.current = null;
    const id = convId.current;
    convId.current = null;
    convKey.current = "";
    setMessages([]);
    setLogs([]);
    setUploadedDoc(null);
    setAttachments([]);
    setWordTemplate(null);
    setSending(false);
    if (id) {
      try {
        await deleteConversation(id);
      } catch {
        // 後端清除失敗不影響前端重新開始;下次送訊息會建立新對話
      }
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      send();
    }
  }

  return (
    <div className="layout">
      <header className="topbar">
        <h1>LLM Agent 平台</h1>
        <div className="model-picker">
          <span className="auto-agent-badge" title="Agent 由路由器依訊息自動選擇(全流程需求自動接力),無須手動切換">
            🤖 Agent 自動路由
          </span>
          <button className="settings-btn" onClick={() => setModal("profiles")} title="Agent Prompt 管理與版本控制">
            Agent 管理
          </button>
          <label>模型</label>
          <select value={model} onChange={(e) => setModel(e.target.value)} disabled={sending}>
            {models.map((m) => (
              <option key={m.id} value={m.id}>{m.label}</option>
            ))}
          </select>
          <span className="provider-tag">ICA · {models.length} 模型</span>
          <button className="settings-btn" onClick={() => setModal("providers")} title="Provider 管理與連線測試">
            Provider
          </button>
          <button
            className="settings-btn"
            onClick={() => setModal("settings")}
            title="設定(System Prompt / API 連線)"
          >
            ⚙ 設定
          </button>
          <button
            className="settings-btn"
            onClick={clearConversation}
            disabled={messages.length === 0 && !convId.current}
            title="清除對話與產出物,重新開始"
          >
            🗑 清除對話
          </button>
        </div>
      </header>

      <main className="main">
        <section className="chat">
          <div className="messages" ref={messagesRef}>
            {messages.length === 0 && (
              <div className="welcome">
                <h2>開始對話</h2>
                <p>輸入需求敘述、附件或 Mural 看板連結,Agent 會自動路由;說「做完全流程」即依
                  規格 → BRD → 計畫 → 實作 → 分析 五階段接力,產出即時呈現在右側對應分頁。</p>
              </div>
            )}
            {messages.map((m) => (
              <MessageView key={m.id} message={m} />
            ))}
          </div>
          <div className="composer">
            {attachments.length > 0 && (
              <div className="attachment-chips">
                {attachments.map((a) => (
                  <button
                    key={a.fileId}
                    className="expand-btn"
                    title="移除附件"
                    onClick={() =>
                      setAttachments((prev) => prev.filter((x) => x.fileId !== a.fileId))
                    }
                  >
                    📎 {a.filename} ✕
                  </button>
                ))}
              </div>
            )}
            <input
              ref={attachInputRef}
              type="file"
              accept=".docx,.txt,.md,.markdown,.feature,.csv,.json,.yaml,.yml,.xml"
              style={{ display: "none" }}
              onChange={async (e) => {
                const f = e.target.files?.[0];
                if (!f) return;
                try {
                  const up = await uploadFile(f);
                  setAttachments((prev) => [...prev, { fileId: up.fileId, filename: up.filename }]);
                } catch {
                  setLogs((prev) => [...prev, { level: "ERROR", source: "client", msg: "附件上傳失敗", ts: "" }]);
                }
                e.target.value = "";
              }}
            />
            <button
              className="attach-btn"
              title="上傳附件(內容將提供給模型參考,支援 .docx / 純文字)"
              onClick={() => attachInputRef.current?.click()}
              disabled={sending}
            >
              📎
            </button>
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder="輸入訊息…(Cmd/Ctrl + Enter 送出;📎 可附加 .docx / 文字檔)"
              rows={3}
              disabled={sending}
            />
            <button
              className="attach-btn"
              title="步驟二一鍵產生:以 BRD 業務文件 Agent 依上則回覆的 Gherkin 產出套版資料,完成後於「Word 預覽」看到原模板填寫結果"
              onClick={sendBrd}
              disabled={sending || !brdProfile || !gherkinBlocks?.length}
            >
              📄 產生 BRD
            </button>
            <button
              className="attach-btn"
              title="依 GitFlow 發布產出:Gherkin 場景開 Issue → feature 分支提交程式碼 → PR 連結 Issues(需先於 ⚙ 設定填 Git Repo/Token)"
              onClick={publishToGit}
              disabled={sending || publishing || javaBlockCount === 0 || !convId.current}
            >
              {publishing ? "發布中…" : "🚀 發布 Git"}
            </button>
            <button onClick={() => send()} disabled={sending || !input.trim()}>
              {sending ? "串流中…" : "送出"}
            </button>
          </div>
        </section>

        <aside className="sidebar">
          <div className="tabs">
            {([
              { key: "spec", label: "📐 規格", stage: 1, has: gherkinBlockCount > 0 },
              { key: "word", label: "📄 BRD", stage: 2, has: !!brdFill || !!uploadedDoc },
              { key: "plan", label: "🧭 計畫", stage: 3, has: stageText.plan.trim().length > 0 },
              { key: "code", label: "💻 實作", stage: 4, has: javaBlockCount > 0 },
              { key: "review", label: "🔍 分析", stage: 5, has: stageText.review.trim().length > 0 },
            ] as { key: Tab; label: string; stage: number; has: boolean }[]).map((t) => (
              <button
                key={t.key}
                className={tab === t.key ? "active" : ""}
                onClick={() => setTab(t.key)}
                title={sending && currentStage === t.stage ? "此階段進行中" : undefined}
              >
                {t.label}
                {sending && currentStage === t.stage ? " ●" : t.has ? " ✓" : ""}
              </button>
            ))}
            <button className={tab === "logs" ? "active" : ""} onClick={() => setTab("logs")}>
              日誌{logs.length > 0 ? ` (${logs.length})` : ""}
            </button>
          </div>
          <div className="tab-body" ref={tabBodyRef}>
            {tab === "spec" && (
              <ArtifactPanel
                sourceMarkdown={lastAssistant?.content ?? ""}
                conversationId={convId.current}
                onExpand={() => setModal("code")}
                filter="GHERKIN"
                emptyHint="尚無 Gherkin 規格。送出需求敘述、附件或 Mural 看板連結開始(Specify 階段)。"
              />
            )}
            {tab === "plan" && (
              stageText.plan.trim()
                ? <div className="stage-md"><Markdown>{stageText.plan}</Markdown></div>
                : <p className="empty">尚無技術計畫。全流程會在 Plan 階段由 DDD 設計 Agent 產出(Bounded Context/聚合根/Command/Event)。</p>
            )}
            {tab === "code" && (
              <ArtifactPanel
                sourceMarkdown={lastAssistant?.content ?? ""}
                conversationId={convId.current}
                onExpand={() => setModal("code")}
                filter="JAVA"
                emptyHint="尚無程式碼。Implement 階段會依技術計畫逐批產出 Java + Cucumber。"
              />
            )}
            {tab === "review" && (
              stageText.review.trim()
                ? <div className="stage-md"><Markdown>{stageText.review}</Markdown></div>
                : <p className="empty">尚無審查報告。Analyze 階段會做品質審查與 Gherkin↔實作↔測試追溯檢查。</p>
            )}
            {tab === "word" && (
              <>
                <div className="word-toolbar">
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept=".docx"
                    style={{ display: "none" }}
                    onChange={async (e) => {
                      const f = e.target.files?.[0];
                      if (!f) return;
                      try {
                        const up = await uploadFile(f);
                        setUploadedDoc({ url: up.previewUrl, name: up.filename });
                      } catch {
                        setLogs((prev) => [...prev, { level: "ERROR", source: "client", msg: "上傳失敗", ts: "" }]);
                      }
                      e.target.value = "";
                    }}
                  />
                  <button className="expand-btn" onClick={() => fileInputRef.current?.click()}>
                    ⬆ 上傳 .docx 預覽
                  </button>
                  {uploadedDoc && (
                    <button className="expand-btn" onClick={() => setUploadedDoc(null)}>
                      ✕ {uploadedDoc.name}
                    </button>
                  )}
                  <input
                    ref={templateInputRef}
                    type="file"
                    accept=".docx"
                    style={{ display: "none" }}
                    onChange={async (e) => {
                      const f = e.target.files?.[0];
                      if (!f) return;
                      try {
                        const up = await uploadFile(f);
                        setWordTemplate({ fileId: up.fileId, filename: up.filename });
                      } catch {
                        setLogs((prev) => [...prev, { level: "ERROR", source: "client", msg: "範本上傳失敗", ts: "" }]);
                      }
                      e.target.value = "";
                    }}
                  />
                  <button
                    className="expand-btn"
                    title="上傳 Word 範本套版(支援 {{title}} / {{content}} 佔位)"
                    onClick={() => templateInputRef.current?.click()}
                  >
                    📄 上傳範本
                  </button>
                  {wordTemplate && (
                    <button
                      className="expand-btn"
                      title="移除範本"
                      onClick={() => setWordTemplate(null)}
                    >
                      ✕ 範本:{wordTemplate.filename}
                    </button>
                  )}
                </div>
                <WordPreview
                  markdown={lastAssistant?.streaming ? "" : (lastAssistant?.content ?? "")}
                  title={wordTitle}
                  fileUrl={uploadedDoc?.url}
                  templateFileId={wordTemplate?.fileId}
                  brdFill={brdFill}
                  onExpand={() => setModal("word")}
                />
              </>
            )}
            {tab === "logs" && <LogPanel logs={logs} />}
          </div>
        </aside>
      </main>

      {modal === "word" && (
        <Modal title={uploadedDoc ? `上傳文件 · ${uploadedDoc.name}` : "業務需求文件 · Word 預覽"} onClose={() => setModal(null)}>
          <WordPreview
            variant="modal"
            markdown={lastAssistant?.content ?? ""}
            title={wordTitle}
            fileUrl={uploadedDoc?.url}
            templateFileId={wordTemplate?.fileId}
            brdFill={brdFill}
          />
        </Modal>
      )}
      {modal === "code" && (
        <Modal title={artifactModalTitle(lastAssistant?.content ?? "")} onClose={() => setModal(null)}>
          <Markdown>{lastAssistant?.content ?? ""}</Markdown>
        </Modal>
      )}
      {pendingConfirm && (
        <Modal title="確認要用的 Agent" onClose={() => setPendingConfirm(null)}>
          <div className="confirm-agent">
            <p>
              這則訊息看起來是<strong>{pendingConfirm.suggestion.reason}</strong>,
              但目前選的 Agent 是「
              {profiles.find((p) => p.id === profileId)?.name
                ?? (profileId === AUTO_PROFILE ? "自動路由" : "(全域預設)")}」。
            </p>
            <div className="confirm-agent-actions">
              <button onClick={() => confirmSend(true)}>
                改用「{pendingConfirm.suggestion.profile.name}」送出
              </button>
              <button className="settings-btn" onClick={() => confirmSend(false)}>
                {profileId === AUTO_PROFILE ? "以全域預設送出" : "仍用目前 Agent 送出"}
              </button>
            </div>
          </div>
        </Modal>
      )}
      {modal === "settings" && <SettingsModal onClose={() => setModal(null)} />}
      {modal === "profiles" && <AgentProfilesModal onClose={() => setModal(null)} />}
      {modal === "providers" && <ProvidersModal onClose={() => setModal(null)} />}
    </div>
  );
}
