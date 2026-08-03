package com.example.llmagent.application.port.out;

/**
 * Git 託管服務 port(GitHub/GitLab):開 Issue、建立分支提交產出程式碼、開 PR。
 * 連線設定(Repo URL / Token)由執行期設定提供;adapter 依 URL host 決定實作。
 */
public interface GitHostPort {

    /** 是否已完成 Git 整合設定(Repo URL 與 Token 皆備)。 */
    boolean configured();

    record IssueRef(int number, String url) {
    }

    /** 建立 Issue,回傳編號與連結。 */
    IssueRef createIssue(String title, String body);

    /** 自預設分支建立分支(已存在則沿用);回傳預設分支名(PR base 用)。 */
    String ensureBranch(String branchName);

    /** 於分支提交單一檔案(不存在則建立,存在則更新)。 */
    void commitFile(String branch, String path, String content, String commitMessage);

    /** 開 Pull Request,回傳 PR 連結。 */
    String openPullRequest(String branch, String baseBranch, String title, String body);
}
