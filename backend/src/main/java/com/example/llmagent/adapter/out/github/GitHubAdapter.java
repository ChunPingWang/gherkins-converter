package com.example.llmagent.adapter.out.github;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.llmagent.application.RuntimeSettingsService;
import com.example.llmagent.application.port.out.GitHostPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GitHub adapter(REST API v3):開 Issue、建分支、Contents API 逐檔提交、開 PR。
 * 連線設定來自 {@link RuntimeSettingsService}(Repo URL / Token,執行期可改、金鑰不落地)。
 * 僅支援 github.com;GitLab 之後以另一 adapter 依 URL host 分流。
 *
 * <p>皆為 blocking HTTP,呼叫端(PublishService/Controller)須於 boundedElastic 執行。
 */
@Component
public class GitHubAdapter implements GitHostPort {

    private static final Pattern REPO_URL =
            Pattern.compile("^https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");

    private final RuntimeSettingsService settings;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubAdapter(RuntimeSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public boolean configured() {
        return REPO_URL.matcher(settings.gitRepoUrl()).matches() && !settings.gitToken().isBlank();
    }

    @Override
    public IssueRef createIssue(String title, String body) {
        JsonNode res = call("POST", "/issues", Map.of("title", title, "body", body), 201);
        return new IssueRef(res.get("number").asInt(), res.get("html_url").asText());
    }

    @Override
    public String ensureBranch(String branchName) {
        JsonNode repo = call("GET", "", null, 200);
        String base = repo.get("default_branch").asText();
        JsonNode ref;
        try {
            ref = call("GET", "/git/ref/heads/" + base, null, 200);
        } catch (GitHubApiException e) {
            // 404/409 = 空 repo(無任何 commit):先建立初始 commit 再取基準 ref
            String readme = Base64.getEncoder().encodeToString(
                    "# 由 SDLC 平台初始化\n".getBytes(StandardCharsets.UTF_8));
            call("PUT", "/contents/README.md",
                    Map.of("message", "chore: 初始化儲存庫(SDLC 平台)", "content", readme), 201);
            ref = call("GET", "/git/ref/heads/" + base, null, 200);
        }
        String sha = ref.get("object").get("sha").asText();
        try {
            call("POST", "/git/refs", Map.of("ref", "refs/heads/" + branchName, "sha", sha), 201);
        } catch (GitHubApiException e) {
            if (e.status != 422) { // 422 = 分支已存在,沿用
                throw e;
            }
        }
        return base;
    }

    @Override
    public void commitFile(String branch, String path, String content, String commitMessage) {
        String encodedPath = encodePath(path);
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        try {
            call("PUT", "/contents/" + encodedPath,
                    Map.of("message", commitMessage, "content", b64, "branch", branch), 201);
        } catch (GitHubApiException e) {
            if (e.status != 422) {
                throw e;
            }
            // 檔案已存在:取 sha 後更新
            JsonNode existing = call("GET", "/contents/" + encodedPath + "?ref=" + branch, null, 200);
            call("PUT", "/contents/" + encodedPath,
                    Map.of("message", commitMessage, "content", b64, "branch", branch,
                            "sha", existing.get("sha").asText()), 200);
        }
    }

    @Override
    public String openPullRequest(String branch, String baseBranch, String title, String body) {
        JsonNode res = call("POST", "/pulls",
                Map.of("title", title, "head", branch, "base", baseBranch, "body", body), 201);
        return res.get("html_url").asText();
    }

    // ---- internal ----

    static class GitHubApiException extends RuntimeException {
        final int status;

        GitHubApiException(int status, String message) {
            super("GitHub API " + status + ": " + message);
            this.status = status;
        }
    }

    private JsonNode call(String method, String pathSuffix, Map<String, ?> body, int expected) {
        Matcher m = REPO_URL.matcher(settings.gitRepoUrl());
        if (!m.matches() || settings.gitToken().isBlank()) {
            throw new IllegalStateException("Git 整合未設定(Repo URL / Token),請至 ⚙ 設定填寫");
        }
        String api = "https://api.github.com/repos/" + m.group(1) + "/" + m.group(2) + pathSuffix;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(api))
                    .header("Authorization", "Bearer " + settings.gitToken())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            if (body == null) {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != expected) {
                throw new GitHubApiException(res.statusCode(), truncate(res.body()));
            }
            return res.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(res.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("GitHub API 呼叫失敗:" + e.getMessage(), e);
        }
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }
}
