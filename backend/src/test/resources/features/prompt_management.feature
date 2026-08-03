# language: zh-TW
功能: System Prompt 檔案隔離與版本控制
  內建 Agent 的 System Prompt 與程式碼隔離,統一存於 /seed/prompts/*.md;
  seeder 以名稱冪等種子化,prompt 檔內容變更時自動 append 新版本;
  版本歷史完整保留(append-only),可還原任一舊版(還原=以舊版內容 append 新版本)。

  場景: 內建四個 Agent 皆以 prompt 資源檔種子化
    當 系統種子化內建 Agent Profile
    那麼 應存在名為 "BDD 規格 Agent" 的 Agent Profile
    而且 應存在名為 "Java 產碼 Agent" 的 Agent Profile
    而且 應存在名為 "Code Review Agent" 的 Agent Profile
    而且 應存在名為 "BRD 業務文件 Agent" 的 Agent Profile

  場景: prompt 內容變更時自動 append 新版本
    假設 以內容 "版本一 prompt" 種子化名為 "測試 Agent" 的內建 Profile
    當 以內容 "版本二 prompt" 再次種子化同名 Profile
    那麼 "測試 Agent" 的最新版本應為 2 且 prompt 為 "版本二 prompt"

  場景: prompt 內容未變時不產生新版本
    假設 以內容 "版本一 prompt" 種子化名為 "測試 Agent" 的內建 Profile
    當 以內容 "版本一 prompt" 再次種子化同名 Profile
    那麼 "測試 Agent" 的最新版本應為 1 且 prompt 為 "版本一 prompt"

  場景: 還原舊版本即以其內容產生新版本
    假設 以內容 "版本一 prompt" 種子化名為 "測試 Agent" 的內建 Profile
    而且 以內容 "版本二 prompt" 再次種子化同名 Profile
    當 還原 "測試 Agent" 至版本 1
    那麼 "測試 Agent" 的最新版本應為 3 且 prompt 為 "版本一 prompt"
    而且 "測試 Agent" 的版本歷史應有 3 筆

  場景: 還原不存在的版本應失敗
    假設 以內容 "版本一 prompt" 種子化名為 "測試 Agent" 的內建 Profile
    那麼 還原 "測試 Agent" 至版本 9 應失敗
