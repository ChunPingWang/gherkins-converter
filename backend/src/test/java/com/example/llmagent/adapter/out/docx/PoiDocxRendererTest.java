package com.example.llmagent.adapter.out.docx;

import java.io.ByteArrayInputStream;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PoiDocxRendererTest {

    private final PoiDocxRenderer renderer = new PoiDocxRenderer();

    @Test
    void rendersHeadingsTablesAndText() throws Exception {
        String md = """
                # 業務需求

                本文件說明 **下單** 流程。

                ## 版本

                | 版本 | 日期 |
                |------|------|
                | 1.0 | 2026 |

                - 項目一
                - 項目二
                """;

        byte[] bytes = renderer.render("測試文件", md);
        assertThat(bytes).isNotEmpty();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String allText = String.join("\n",
                    doc.getParagraphs().stream().map(p -> p.getText()).toList());
            assertThat(allText).contains("測試文件");   // 標題
            assertThat(allText).contains("業務需求");
            assertThat(allText).contains("下單");        // 粗體內文
            assertThat(allText).contains("• 項目一");
            // 表格
            assertThat(doc.getTables()).hasSize(1);
            assertThat(doc.getTables().get(0).getText()).contains("版本").contains("2026");
        }
    }
}
