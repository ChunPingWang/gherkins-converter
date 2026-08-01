# language: zh-TW
功能: Mural MCP 參數執行期設定
  Mural 連線參數(啟用/Client ID/Secret)初始來自環境變數,可經設定 API 於執行期修改;
  僅存記憶體、金鑰遮罩呈現(CLAUDE.md #8),參數版本遞增通知工具 adapter 重連。

  場景: 執行期更新 Mural 參數後版本遞增且生效
    假設 Mural MCP 初始為停用
    當 於執行期啟用 Mural 並設定 Client ID "abc-123"
    那麼 Mural 參數版本應遞增
    而且 Mural 設定應為啟用且 Client ID 為 "abc-123"

  場景: 未變更任何 Mural 欄位時版本不動
    假設 Mural MCP 初始為停用
    當 以全空欄位更新 Mural 設定
    那麼 Mural 參數版本應維持不變

  場景: Mural Client Secret 遮罩呈現
    當 於執行期設定 Mural Client Secret "377d37496e0732e2974b9f9468971adc"
    那麼 遮罩後的 Mural Secret 應以 "377d" 開頭且不含完整明碼

  場景: 停用 Mural 時工具提供者回傳空清單
    假設 Mural MCP 初始為停用
    那麼 Mural 工具提供者應回傳 0 個工具且不啟動連線
