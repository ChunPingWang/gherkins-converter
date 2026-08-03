# language: zh-TW
功能: SDLC 流水線編排(層次二 Orchestrator)
  於同一對話依序協調 BDD 規格 → BRD 業務文件 → Java 產碼 → Code Review 四個 Agent,
  前一步驟產出(Gherkin/程式碼)自動餵給下一步驟;單一 SSE 串流全程,
  中間步驟 done 轉 log、僅最終步驟發 done;缺必要產出物時優雅中止。

  背景:
    假設 已種子化流水線所需的四個 Agent

  場景: 全流程四步驟依序執行且產出物接力
    假設 流水線模型將依序回覆 Gherkin、BRD JSON、Java 程式碼、審查意見
    當 以目標 "從需求做完全流程:使用者登入" 啟動流水線並收完串流
    那麼 串流應依序出現四個步驟的開始 log
    而且 對話應有 4 則 assistant 訊息
    而且 步驟 2 的輸入應包含步驟 1 的 Gherkin 內容
    而且 步驟 4 的輸入應包含步驟 3 的 Java 程式碼
    而且 串流應恰有 1 個 done 事件且位於最後
    而且 對話應存在 "GHERKIN" 與 "JAVA" 產出物

  場景: 步驟 1 未產出 Gherkin 時流程優雅中止
    假設 流水線模型將於步驟 1 回覆純文字 "我無法判斷需求"
    當 以目標 "做完全流程" 啟動流水線並收完串流
    那麼 對話應只有 1 則 assistant 訊息
    而且 串流應含 source 為 "orchestrator" 的 ERROR log
    而且 串流應恰有 1 個 done 事件且位於最後

  場景: 流水線所需 Agent 不存在時無法啟動
    假設 未種子化 "Java 產碼 Agent"
    那麼 啟動流水線應失敗並提示缺少 "Java 產碼 Agent"
