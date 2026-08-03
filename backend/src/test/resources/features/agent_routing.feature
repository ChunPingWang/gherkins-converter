# language: zh-TW
功能: Agent 自動路由(層次一)
  使用者選「自動」時,以一次輕量 LLM 呼叫判斷訊息意圖,回傳決策物件
  (target/agentProfileId/confidence/reason)。任何偏差一律降級 NONE 退回人工選擇,
  不阻斷送出;決策物件之 target 預留 PIPELINE 給層次二流程編排。

  背景:
    假設 存在候選 Agent "BDD 規格 Agent" 與 "Java 產碼 Agent"

  場景: 模型明確判斷時路由至對應 Agent
    假設 路由模型將回傳 BDD Agent 的 JSON 決策且信心為 0.9 理由 "要求撰寫 Gherkin"
    當 對訊息 "幫我把需求轉成 Gherkin" 進行路由
    那麼 路由決策 target 應為 "AGENT" 且對應 "BDD 規格 Agent"
    而且 路由決策信心應為 0.9 且理由為 "要求撰寫 Gherkin"

  場景: 模型回覆包在 think 標籤中仍可解析
    假設 路由模型將回傳含 think 標籤包裹的 BDD Agent JSON 決策
    當 對訊息 "幫我把需求轉成 Gherkin" 進行路由
    那麼 路由決策 target 應為 "AGENT" 且對應 "BDD 規格 Agent"

  場景: 模型無法判斷時決策為 NONE
    假設 路由模型將回傳 none 決策
    當 對訊息 "你好" 進行路由
    那麼 路由決策 target 應為 "NONE"

  場景: 模型回覆非 JSON 時降級為 NONE
    假設 路由模型將回傳 "我覺得應該用 BDD Agent"
    當 對訊息 "幫我把需求轉成 Gherkin" 進行路由
    那麼 路由決策 target 應為 "NONE"

  場景: 模型回傳未知 Agent id 時降級為 NONE
    假設 路由模型將回傳未知 Agent id 的 JSON 決策
    當 對訊息 "幫我把需求轉成 Gherkin" 進行路由
    那麼 路由決策 target 應為 "NONE"

  場景: 使用者要求全流程時路由至 PIPELINE
    假設 路由模型將回傳 pipeline 決策且信心為 0.95
    當 對訊息 "從這個 Mural 看板一條龍做完 Gherkin、BRD、Java 與審查" 進行路由
    那麼 路由決策 target 應為 "PIPELINE"

  場景: 路由呼叫不掛載任何工具
    假設 路由模型將回傳 none 決策
    當 對訊息 "讀取 Mural 看板" 進行路由
    那麼 路由呼叫傳給 Provider 的工具名單應為空
