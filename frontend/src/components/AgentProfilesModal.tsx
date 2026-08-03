import { useEffect, useState } from "react";
import {
  fetchAgentProfiles,
  fetchAgentProfileVersions,
  restoreAgentProfileVersion,
  saveAgentProfile,
  type AgentProfile,
} from "../api";
import { Modal } from "./Modal";

/**
 * Agent Profile 管理視窗(WP2-T6,ADR-006)。
 * 修改 prompt 即產生新版本(append-only);可檢視版本歷史。
 */
export function AgentProfilesModal({ onClose }: { onClose: () => void }) {
  const [profiles, setProfiles] = useState<AgentProfile[]>([]);
  const [selected, setSelected] = useState<AgentProfile | null>(null);
  const [versions, setVersions] = useState<AgentProfile[]>([]);
  const [showVersions, setShowVersions] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [prompt, setPrompt] = useState("");
  const [status, setStatus] = useState<"idle" | "saving" | "saved" | "error">("idle");

  async function reload(selectId?: string) {
    const list = await fetchAgentProfiles();
    setProfiles(list);
    const sel = selectId ? (list.find((p) => p.id === selectId) ?? null) : (list[0] ?? null);
    select(sel);
  }

  function select(p: AgentProfile | null) {
    setSelected(p);
    setName(p?.name ?? "");
    setDescription(p?.description ?? "");
    setPrompt(p?.systemPrompt ?? "");
    setVersions([]);
    setShowVersions(false);
  }

  useEffect(() => {
    reload().catch(() => setStatus("error"));
  }, []);

  async function save() {
    setStatus("saving");
    try {
      const saved = await saveAgentProfile(selected?.id ?? null, {
        name,
        description,
        systemPrompt: prompt,
      });
      await reload(saved.id);
      setStatus("saved");
      setTimeout(() => setStatus("idle"), 1800);
    } catch {
      setStatus("error");
    }
  }

  async function toggleVersions() {
    if (!selected) return;
    if (!showVersions && versions.length === 0) {
      setVersions(await fetchAgentProfileVersions(selected.id));
    }
    setShowVersions((v) => !v);
  }

  /** 還原至舊版本:以該版內容 append 為新版本,並重新載入。 */
  async function restore(version: number) {
    if (!selected) return;
    setStatus("saving");
    try {
      const restored = await restoreAgentProfileVersion(selected.id, version);
      await reload(restored.id);
      setStatus("saved");
      setTimeout(() => setStatus("idle"), 1800);
    } catch {
      setStatus("error");
    }
  }

  return (
    <Modal
      title="Agent Profile 管理"
      onClose={onClose}
      actions={
        <button onClick={save} disabled={status === "saving" || !name.trim() || !prompt.trim()}>
          {status === "saving" ? "儲存中…" : status === "saved" ? "已存新版本 ✓" : selected ? "儲存(新版本)" : "建立"}
        </button>
      }
    >
      <div className="profiles-layout">
        <aside className="profiles-list">
          {profiles.map((p) => (
            <button
              key={p.id}
              className={`profile-item ${selected?.id === p.id ? "active" : ""}`}
              onClick={() => select(p)}
            >
              <span className="profile-name">{p.name}</span>
              <span className="profile-ver">v{p.version}</span>
            </button>
          ))}
          <button className="profile-item profile-new" onClick={() => select(null)}>
            ＋ 新增 Profile
          </button>
        </aside>

        <section className="profile-editor">
          <label className="field">
            <span>名稱</span>
            <input value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="field">
            <span>描述</span>
            <input value={description} onChange={(e) => setDescription(e.target.value)} />
          </label>
          <label className="field">
            <span>
              System Prompt(支援 {"{project_name}"}、{"{gherkin_locale}"} 等範本變數;修改後儲存即產生新版本)
            </span>
            <textarea
              className="settings-prompt"
              rows={12}
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              spellCheck={false}
            />
          </label>

          {selected && (
            <div className="versions">
              <button className="expand-btn" onClick={toggleVersions}>
                {showVersions ? "▾" : "▸"} 版本歷史(目前 v{selected.version})
              </button>
              {showVersions && (
                <ul className="version-list">
                  {versions.map((v) => (
                    <li key={v.version}>
                      <details>
                        <summary>
                          v{v.version} · {v.name}
                          {v.createdAt && ` · ${new Date(v.createdAt).toLocaleString()}`}
                          {v.version === selected.version && "(目前)"}
                        </summary>
                        <pre>{v.systemPrompt}</pre>
                        {v.version !== selected.version && (
                          <button
                            className="settings-btn"
                            disabled={status === "saving"}
                            onClick={() => restore(v.version)}
                          >
                            還原此版(以 v{v.version} 內容建立新版本)
                          </button>
                        )}
                      </details>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
          {status === "error" && <p className="empty">操作失敗,請稍後再試。</p>}
        </section>
      </div>
    </Modal>
  );
}
