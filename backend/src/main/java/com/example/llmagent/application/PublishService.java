package com.example.llmagent.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.example.llmagent.application.port.out.GitHostPort;
import com.example.llmagent.domain.artifact.Artifact;

/**
 * 產出物發布服務:把對話的 SDLC 產出依 GitFlow 慣例寫入 Git 託管服務──
 * <b>需求先開 Issue、程式碼走分支開 PR</b>:
 *
 * <ol>
 *   <li>Gherkin 每個「場景」開一張 Issue(需求追蹤單位;body 附完整場景文字)</li>
 *   <li>自預設分支建立 {@code feature/sdlc-<conv>} 分支,逐檔提交產出程式碼
 *       (路徑取自各檔首行註解;另提交 feature 檔)</li>
 *   <li>開 PR:body 以 {@code Closes #n} 連結全部 Issues,merge 時自動關單,
 *       形成「需求 → 規格 → 程式碼 → PR」完整追溯鏈</li>
 * </ol>
 */
@Service
public class PublishService {

    /** Gherkin 場景標題(場景:/場景大綱:)。 */
    private static final Pattern SCENARIO = Pattern.compile("(?m)^\\s*場景(?:大綱)?[::]\\s*(.+?)\\s*$");
    /** 產碼檔首行路徑註解(// src/... 或 # src/...)。 */
    private static final Pattern PATH_COMMENT = Pattern.compile("^\\s*(?://|#)\\s*(\\S+\\.(?:java|feature|xml|yml|yaml|properties))\\s*$");

    private final ArtifactService artifacts;
    private final GitHostPort git;

    public PublishService(ArtifactService artifacts, GitHostPort git) {
        this.artifacts = artifacts;
        this.git = git;
    }

    public record PublishResult(List<GitHostPort.IssueRef> issues, String branch, String prUrl,
                                int fileCount) {
    }

    public PublishResult publish(String conversationId) {
        if (!git.configured()) {
            throw new IllegalStateException("Git 整合未設定(Repo URL / Token),請至 ⚙ 設定填寫");
        }
        List<Artifact> javaArtifacts = artifacts.versions(conversationId, Artifact.ArtifactType.JAVA);
        if (javaArtifacts.isEmpty()) {
            throw new IllegalStateException("此對話尚無程式碼產出物可發布");
        }
        String gherkin = latestGherkin(conversationId);

        // 1) 需求開 Issue:每個 Gherkin 場景一張
        List<GitHostPort.IssueRef> issues = new ArrayList<>();
        for (String scenario : scenarioTitles(gherkin)) {
            issues.add(git.createIssue("【需求場景】" + scenario,
                    "由 SDLC 平台自動開立(對話 " + shortId(conversationId) + ")。\n\n完整規格:\n\n```gherkin\n"
                            + gherkin + "\n```"));
        }

        // 2) 分支 + 逐檔提交
        String branch = "feature/sdlc-" + shortId(conversationId);
        String base = git.ensureBranch(branch);
        Map<String, String> files = collectFiles(javaArtifacts, gherkin);
        for (Map.Entry<String, String> f : files.entrySet()) {
            git.commitFile(branch, f.getKey(), f.getValue(),
                    "feat: " + f.getKey() + "(SDLC 平台自動產出)");
        }

        // 3) 開 PR,Closes 連結全部 Issues
        StringBuilder body = new StringBuilder("SDLC 平台自動產出(對話 ")
                .append(shortId(conversationId)).append("):Gherkin 規格對應之完整實作與測試,共 ")
                .append(files.size()).append(" 個檔案。\n\n");
        for (GitHostPort.IssueRef issue : issues) {
            body.append("Closes #").append(issue.number()).append('\n');
        }
        String prUrl = git.openPullRequest(branch, base,
                "SDLC 自動產出:" + shortId(conversationId), body.toString());
        return new PublishResult(issues, branch, prUrl, files.size());
    }

    /** 逐檔收集:路徑取自首行註解,重複路徑以較新版本為準;無路徑者入 generated/ 後備目錄。 */
    private Map<String, String> collectFiles(List<Artifact> javaArtifacts, String gherkin) {
        Map<String, String> files = new LinkedHashMap<>();
        int unnamed = 0;
        for (Artifact a : javaArtifacts) {
            String content = a.content();
            String firstLine = content.lines().findFirst().orElse("");
            Matcher m = PATH_COMMENT.matcher(firstLine);
            String path = m.matches() ? m.group(1)
                    : "src/generated/Unnamed" + (++unnamed) + ".java";
            files.put(path, content);
        }
        if (gherkin != null) {
            files.put("src/test/resources/features/generated.feature", gherkin);
        }
        return files;
    }

    private String latestGherkin(String conversationId) {
        List<Artifact> list = artifacts.versions(conversationId, Artifact.ArtifactType.GHERKIN);
        return list.isEmpty() ? null : list.get(list.size() - 1).content();
    }

    static List<String> scenarioTitles(String gherkin) {
        List<String> titles = new ArrayList<>();
        if (gherkin == null) {
            return titles;
        }
        Matcher m = SCENARIO.matcher(gherkin);
        while (m.find()) {
            titles.add(m.group(1));
        }
        return titles;
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
