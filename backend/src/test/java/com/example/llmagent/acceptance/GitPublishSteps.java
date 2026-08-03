package com.example.llmagent.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.llmagent.adapter.out.persistence.InMemoryArtifactStore;
import com.example.llmagent.application.ArtifactService;
import com.example.llmagent.application.PublishService;
import com.example.llmagent.application.port.out.GitHostPort;

import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Git 發布 steps。fake GitHostPort 記錄呼叫,離線決定性執行。 */
public class GitPublishSteps {

    private static final String GHERKIN = """
            # language: zh-TW
            功能: 線上下單
              場景: 成功下單
                假設 會員已登入
              場景: 庫存不足遭拒
                假設 商品庫存為 0
            """;

    /** fake Git host:記錄 issue/分支/檔案/PR。 */
    private static class FakeGitHost implements GitHostPort {
        boolean configured = true;
        final List<String[]> issues = new ArrayList<>();
        String branch;
        final Map<String, String> files = new LinkedHashMap<>();
        String prBody;
        String prBase;

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public IssueRef createIssue(String title, String body) {
            issues.add(new String[]{title, body});
            return new IssueRef(issues.size(), "http://git.test/issues/" + issues.size());
        }

        @Override
        public String ensureBranch(String branchName) {
            this.branch = branchName;
            return "main";
        }

        @Override
        public void commitFile(String b, String path, String content, String message) {
            files.put(path, content);
        }

        @Override
        public String openPullRequest(String b, String base, String title, String body) {
            this.prBody = body;
            this.prBase = base;
            return "http://git.test/pr/1";
        }
    }

    private FakeGitHost gitHost;
    private ArtifactService artifactService;
    private PublishService publishService;
    private String conversationId;
    private PublishService.PublishResult result;

    @Before
    public void setup() {
        gitHost = new FakeGitHost();
        artifactService = new ArtifactService(new InMemoryArtifactStore());
        publishService = new PublishService(artifactService, gitHost);
        conversationId = UUID.randomUUID().toString();
    }

    @Given("Git 整合已設定")
    public void gitConfigured() {
        gitHost.configured = true;
    }

    @Given("Git 整合未設定")
    public void gitNotConfigured() {
        gitHost.configured = false;
    }

    @And("對話有含 2 個場景的 Gherkin 與 2 個 Java 產出物")
    public void conversationHasArtifacts() {
        artifactService.extractAndStore(conversationId, "m1", "```gherkin\n" + GHERKIN + "```");
        artifactService.extractAndStore(conversationId, "m2",
                "```java\n// src/main/java/com/example/order/Order.java\npublic class Order {}\n```\n"
                        + "```java\n// src/test/java/com/example/order/OrderTest.java\nclass OrderTest {}\n```");
    }

    @When("發布該對話產出")
    public void publish() {
        result = publishService.publish(conversationId);
        assertNotNull(result);
    }

    @Then("應開立 {int} 張 Issue 且標題含場景名稱")
    public void issuesCreated(int count) {
        assertEquals(count, gitHost.issues.size());
        assertTrue(gitHost.issues.get(0)[0].contains("成功下單"), gitHost.issues.get(0)[0]);
        assertTrue(gitHost.issues.get(1)[0].contains("庫存不足遭拒"), gitHost.issues.get(1)[0]);
    }

    @And("應自預設分支建立 {string} 開頭的分支")
    public void branchCreated(String prefix) {
        assertTrue(gitHost.branch.startsWith(prefix), gitHost.branch);
        assertEquals("main", gitHost.prBase);
    }

    @And("提交檔案應含首行註解指定的路徑與 feature 檔")
    public void filesCommitted() {
        assertTrue(gitHost.files.containsKey("src/main/java/com/example/order/Order.java"),
                gitHost.files.keySet().toString());
        assertTrue(gitHost.files.containsKey("src/test/java/com/example/order/OrderTest.java"));
        assertTrue(gitHost.files.containsKey("src/test/resources/features/generated.feature"));
        assertEquals(3, result.fileCount());
    }

    @And("PR body 應以 Closes 連結全部 Issue")
    public void prLinksIssues() {
        assertTrue(gitHost.prBody.contains("Closes #1"), gitHost.prBody);
        assertTrue(gitHost.prBody.contains("Closes #2"));
        assertEquals("http://git.test/pr/1", result.prUrl());
    }

    @Then("發布應失敗並提示 {string}")
    public void publishFails(String hint) {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> publishService.publish(conversationId));
        assertTrue(ex.getMessage().contains(hint), ex.getMessage());
    }
}
