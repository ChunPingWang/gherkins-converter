# language: zh-TW
功能: 產出物發布至 Git 託管服務(GitFlow)
  依 GitFlow 慣例:需求(Gherkin 場景)先開 Issue → feature 分支逐檔提交產出程式碼 →
  開 PR 以 Closes #n 連結全部 Issues(merge 自動關單),形成需求→規格→程式碼→PR 追溯鏈。
  Git 連線(Repo URL / Token)為執行期設定,金鑰遮罩不落地。

  場景: 發布對話產出:場景開 Issue、分支提交、PR 連結 Issues
    假設 Git 整合已設定
    而且 對話有含 2 個場景的 Gherkin 與 2 個 Java 產出物
    當 發布該對話產出
    那麼 應開立 2 張 Issue 且標題含場景名稱
    而且 應自預設分支建立 "feature/sdlc-" 開頭的分支
    而且 提交檔案應含首行註解指定的路徑與 feature 檔
    而且 PR body 應以 Closes 連結全部 Issue

  場景: Git 未設定時發布應失敗
    假設 Git 整合未設定
    而且 對話有含 2 個場景的 Gherkin 與 2 個 Java 產出物
    那麼 發布應失敗並提示 "Git 整合未設定"

  場景: 無程式碼產出物時發布應失敗
    假設 Git 整合已設定
    那麼 發布應失敗並提示 "尚無程式碼產出物"
