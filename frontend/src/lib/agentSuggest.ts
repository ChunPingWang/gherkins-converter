import type { AgentProfile } from "../api";

/**
 * Agent 建議(AI 建議 + 使用者確認):以確定性規則偵測訊息意圖,
 * 命中且與目前 Agent 不同時回傳建議(含步驟說明);由使用者按「改用」確認,不自動切換。
 * 規則依特異性排序,先命中先贏。
 */
const RULES: {
  name: string;
  reason: string;
  test: (text: string, attachments: string[]) => boolean;
}[] = [
  {
    name: "BDD 規格 Agent",
    reason: "讀取 Mural 看板(需 Mural 工具授權)",
    test: (t) => /mural/i.test(t),
  },
  {
    name: "BRD 業務文件 Agent",
    reason: "步驟二(Gherkin 轉業務需求文件)",
    test: (t, files) =>
      /brd|業務需求文件|業務文件|需求文件/i.test(t) || files.some((f) => f.endsWith(".feature")),
  },
  {
    name: "Code Review Agent",
    reason: "程式碼審查",
    test: (t) => /code\s*review|審查|reviewer/i.test(t),
  },
  {
    name: "Java 產碼 Agent",
    reason: "產生 Java 程式碼",
    test: (t) => /java|程式碼|產碼|step\s*definitions?/i.test(t),
  },
  {
    name: "BDD 規格 Agent",
    reason: "步驟一(需求轉 Gherkin)",
    test: (t) => /gherkin|\.feature|使用情境|使用者故事|情境撰寫|正反向/i.test(t),
  },
];

export interface AgentSuggestion {
  profile: AgentProfile;
  reason: string;
}

export function suggestAgent(
  text: string,
  attachmentNames: string[],
  profiles: AgentProfile[],
  currentProfileId: string,
): AgentSuggestion | null {
  if (!text.trim() && attachmentNames.length === 0) return null;
  for (const rule of RULES) {
    if (rule.test(text, attachmentNames)) {
      const profile = profiles.find((p) => p.name === rule.name);
      if (profile && profile.id !== currentProfileId) {
        return { profile, reason: rule.reason };
      }
      return null; // 已在建議的 Agent 上,或該 Profile 不存在
    }
  }
  return null;
}
