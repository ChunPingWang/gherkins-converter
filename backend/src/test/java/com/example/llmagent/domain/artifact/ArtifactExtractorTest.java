package com.example.llmagent.domain.artifact;

import org.junit.jupiter.api.Test;

import com.example.llmagent.domain.artifact.Artifact.ArtifactType;
import com.example.llmagent.domain.artifact.ArtifactExtractor.Extraction;

import static org.assertj.core.api.Assertions.assertThat;

/** WP5-T1 驗收:fence 抽取 + 格式偏差降級為 MARKDOWN。 */
class ArtifactExtractorTest {

    @Test
    void extractsGherkinAndJavaOnly() {
        String md = """
                說明文字
                ```gherkin
                功能: 下單
                ```
                中間
                ```java
                class A {}
                ```
                ```bash
                echo skip
                ```
                """;
        Extraction e = ArtifactExtractor.extract(md);
        assertThat(e.degraded()).isFalse();
        assertThat(e.pieces()).hasSize(2);
        assertThat(e.pieces().get(0).type()).isEqualTo(ArtifactType.GHERKIN);
        assertThat(e.pieces().get(0).content()).isEqualTo("功能: 下單");
        assertThat(e.pieces().get(1).type()).isEqualTo(ArtifactType.JAVA);
    }

    @Test
    void unclosedFenceDegradesWholeContentToMarkdown() {
        String md = "前言\n```java\nclass Broken {"; // 未閉合
        Extraction e = ArtifactExtractor.extract(md);
        assertThat(e.degraded()).isTrue();
        assertThat(e.pieces()).hasSize(1);
        assertThat(e.pieces().get(0).type()).isEqualTo(ArtifactType.MARKDOWN);
        assertThat(e.pieces().get(0).content()).contains("class Broken");
    }

    @Test
    void plainChatYieldsNoArtifacts() {
        Extraction e = ArtifactExtractor.extract("純聊天,沒有程式碼。");
        assertThat(e.pieces()).isEmpty();
        assertThat(e.degraded()).isFalse();
    }
}
