# language: zh-TW
功能: MCP 工具呼叫串流(Mural 看板讀取)
  Agent Profile 以 tools 欄位授權外部 MCP 工具;串流時工具執行進度以 tool_call 事件
  (started/finished/error)即時呈現(ADR-003),並落一筆 log 供稽核。

  場景: 啟用 mural 工具的 Agent 串流帶出 tool_call 事件
    假設 存在啟用工具 "mural" 的 Agent Profile "看板 Agent"
    而且 模型將先呼叫工具 "mural_list_widgets" 再回覆 "已讀取看板內容"
    當 以該 Agent Profile 送出訊息 "讀取 Mural 看板"
    那麼 串流應依序出現 "tool_call" 再 "content" 再 "done"
    而且 tool_call 事件應依序為 "mural_list_widgets" 之 "started" 與 "finished"
    而且 串流內容合併後應為 "已讀取看板內容"
    而且 應有一筆 source 為 "tool" 的 log 事件
    而且 傳給 Provider 的工具名單應為 "mural"

  場景: 未指定 Agent Profile 時不掛載任何工具
    假設 模型將以純文字回覆 "純文字回覆"
    當 未指定 Agent Profile 送出訊息 "哈囉"
    那麼 傳給 Provider 的工具名單應為空

  場景: Agent Profile 未授權工具時不掛載任何工具
    假設 存在未啟用工具的 Agent Profile "純文字 Agent"
    而且 模型將以純文字回覆 "純文字回覆"
    當 以該 Agent Profile 送出訊息 "哈囉"
    那麼 傳給 Provider 的工具名單應為空

  場景: 工具執行失敗以 error 狀態呈現且串流正常收尾
    假設 存在啟用工具 "mural" 的 Agent Profile "看板 Agent"
    而且 模型呼叫工具 "mural_list_widgets" 將失敗後回覆 "無法讀取看板"
    當 以該 Agent Profile 送出訊息 "讀取 Mural 看板"
    那麼 tool_call 事件應依序為 "mural_list_widgets" 之 "started" 與 "error"
    而且 串流應依序出現 "tool_call" 再 "content" 再 "done"
