export type Role = "user" | "assistant";

export interface LogLine {
  level: "INFO" | "WARN" | "ERROR" | string;
  source: string;
  msg: string;
  ts: string;
}

/** tool_call SSE 事件(openapi):MCP 工具執行進度。 */
export interface ToolCallEvent {
  name: string;
  arguments: unknown;
  status: "started" | "finished" | "error" | string;
}

export interface Usage {
  promptTokens: number;
  completionTokens: number;
}

export interface DoneInfo {
  usage: Usage;
  elapsedMs: number;
  ttftMs: number;
}

export interface ChatMessage {
  id: string;
  role: Role;
  content: string;
  thinking: string;
  logs: LogLine[];
  done?: DoneInfo;
  streaming: boolean;
  model?: string;
}

export interface ModelOption {
  id: string;
  label: string;
}
