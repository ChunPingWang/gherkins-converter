import { useState } from "react";
import { downloadArtifact, extractArtifacts, type Artifact, type ArtifactType } from "../lib/artifacts";
import { fetchArtifactVersions, type ServerArtifact } from "../api";
import { diffLines, type DiffLine } from "../lib/diff";
import { Markdown } from "./Markdown";

/** Artifact 面板:抽取 Gherkin / Java code fence,提供高亮、複製、下載與版本 diff(R2–R4、WP5-T3)。 */
export function ArtifactPanel({
  sourceMarkdown,
  conversationId,
  onExpand,
  filter,
  emptyHint,
}: {
  sourceMarkdown: string;
  conversationId?: string | null;
  onExpand?: () => void;
  /** 僅顯示指定型別(階段 Tab 用:規格=GHERKIN、實作=JAVA);未指定則全列。 */
  filter?: ArtifactType;
  emptyHint?: string;
}) {
  const artifacts = extractArtifacts(sourceMarkdown)
    .filter((a) => !filter || a.type === filter);
  const [diff, setDiff] = useState<{ type: string; from: number; to: number; lines: DiffLine[] } | null>(null);

  async function showDiff(type: ServerArtifact["type"]) {
    if (!conversationId) return;
    const versions = await fetchArtifactVersions(conversationId, type);
    if (versions.length < 2) {
      setDiff({ type, from: 0, to: versions.length, lines: [] });
      return;
    }
    const prev = versions[versions.length - 2];
    const curr = versions[versions.length - 1];
    setDiff({
      type,
      from: prev.version,
      to: curr.version,
      lines: diffLines(prev.content, curr.content),
    });
  }

  if (artifacts.length === 0) {
    return (
      <p className="empty">
        {emptyHint ?? "尚無產出物。當助理回覆包含 Gherkin 或 Java code fence 時,會在此列出。"}
      </p>
    );
  }

  return (
    <div className="artifacts">
      {onExpand && (
        <div className="word-toolbar">
          <span className="word-status">{artifacts.length} 個產出物</span>
          <div style={{ display: "flex", gap: "0.4rem" }}>
            {conversationId && (
              <button className="expand-btn" onClick={() => showDiff("GHERKIN")}>
                版本 diff
              </button>
            )}
            <button className="expand-btn" onClick={onExpand}>⤢ 放大</button>
          </div>
        </div>
      )}
      {diff && (
        <div className="diff-view">
          <div className="word-toolbar">
            <span className="word-status">
              {diff.lines.length === 0
                ? `${diff.type} 尚無兩個版本可比對(目前 ${diff.to} 版)`
                : `${diff.type} v${diff.from} → v${diff.to}`}
            </span>
            <button className="expand-btn" onClick={() => setDiff(null)}>關閉</button>
          </div>
          {diff.lines.length > 0 && (
            <pre className="diff-body">
              {diff.lines.map((l, i) => (
                <div key={i} className={`diff-${l.kind}`}>
                  {l.kind === "add" ? "+ " : l.kind === "del" ? "- " : "  "}
                  {l.text}
                </div>
              ))}
            </pre>
          )}
        </div>
      )}
      {artifacts.map((a) => (
        <ArtifactCard key={a.index} artifact={a} />
      ))}
    </div>
  );
}

function ArtifactCard({ artifact }: { artifact: Artifact }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    await navigator.clipboard.writeText(artifact.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  return (
    <div className="artifact-card">
      <div className="artifact-head">
        <span className={`badge badge-${artifact.type.toLowerCase()}`}>{artifact.type}</span>
        <span className="artifact-lang">.{artifact.ext}</span>
        <div className="artifact-actions">
          <button onClick={copy}>{copied ? "已複製" : "複製"}</button>
          <button onClick={() => downloadArtifact(artifact)}>下載</button>
        </div>
      </div>
      <Markdown>{"```" + artifact.lang + "\n" + artifact.content + "\n```"}</Markdown>
    </div>
  );
}
