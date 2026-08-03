# language: zh-TW
功能: SDLC 流水線編排(層次二 Orchestrator,部分對齊 Spec-Kit)
  五個 Agent 於同一對話依序接力:Specify(BDD 規格 → BRD)→ Plan(DDD 設計)→
  Implement(Java 產碼,依 Plan 的 CONTEXTS 逐批)→ Analyze(Code Review + 追溯)。
  前一步驟產出自動餵給下一步驟;單一 SSE 串流,中間步驟 done 轉 log、僅最終步驟發 done;
  缺必要產出物時優雅中止;串流中斷自動重試一次。

  背景:
    假設 已種子化流水線所需的五個 Agent

  場景: 全流程五步驟依序執行且產出物接力(Plan 未分批)
    假設 流水線模型將依序回覆 Gherkin、BRD JSON、無分批標記的 Plan、Java 程式碼、審查意見
    當 以目標 "從需求做完全流程:使用者登入" 啟動流水線並收完串流
    那麼 串流應依序出現 5 個步驟的開始 log
    而且 對話應有 5 則 assistant 訊息
    而且 步驟 2 的輸入應包含步驟 1 的 Gherkin 內容
    而且 產碼輸入應包含 DDD 技術計畫
    而且 審查輸入應包含步驟 4 的 Java 程式碼與 Gherkin
    而且 串流應恰有 1 個 done 事件且位於最後
    而且 對話應存在 "GHERKIN" 與 "JAVA" 產出物

  場景: Plan 含 CONTEXTS 標記時 Implement 逐批產碼
    假設 流水線模型的 Plan 將標記 CONTEXTS 為 "ordering, inventory"
    當 以目標 "做完全流程" 啟動流水線並收完串流
    那麼 Provider 應被呼叫 6 次
    而且 產碼批次 1 的輸入應限定 context "ordering"
    而且 產碼批次 2 的輸入應限定 context "inventory"
    而且 審查輸入應包含全部批次的程式碼
    而且 串流應恰有 1 個 done 事件且位於最後

  場景: 步驟 1 未產出 Gherkin 時流程優雅中止
    假設 流水線模型將於步驟 1 回覆純文字 "我無法判斷需求"
    當 以目標 "做完全流程" 啟動流水線並收完串流
    那麼 對話應只有 1 則 assistant 訊息
    而且 串流應含 source 為 "orchestrator" 的 ERROR log
    而且 串流應恰有 1 個 done 事件且位於最後

  場景: 步驟串流中斷時自動重試一次
    假設 流水線模型產碼步驟首次將中斷,重試與其他步驟正常回覆
    當 以目標 "做完全流程" 啟動流水線並收完串流
    那麼 串流應含 source 為 "orchestrator" 的 WARN 重試 log
    而且 Provider 應被呼叫 6 次
    而且 對話應有 5 則 assistant 訊息
    而且 串流應恰有 1 個 done 事件且位於最後

  場景: 重試仍失敗時流程繼續不掛死
    假設 流水線模型產碼步驟連兩次中斷,其他步驟正常回覆
    當 以目標 "做完全流程" 啟動流水線並收完串流
    那麼 Provider 應被呼叫 6 次
    而且 對話應有 4 則 assistant 訊息
    而且 串流應恰有 1 個 done 事件且位於最後

  場景: 流水線所需 Agent 不存在時無法啟動
    假設 未種子化 "Java 產碼 Agent"
    那麼 啟動流水線應失敗並提示缺少 "Java 產碼 Agent"
