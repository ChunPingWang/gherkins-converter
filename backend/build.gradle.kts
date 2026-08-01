plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.example"
version = "0.1.0-SNAPSHOT"

extra["springAiVersion"] = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

dependencies {
    // Web / SSE (reactive per ADR-002: Reactor Flux<ServerSentEvent>)
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // AI 抽象:Spring AI OpenAI ChatModel(ADR-001)。ICA 為 OpenAI-Compatible Gateway。
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // MCP client(stdio):對話中掛載外部工具(如 Mural 看板讀取),tool_call 事件見 ADR-003。
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")

    // Word (.docx) 產生(WP6-T3,ADR-004):後端以 Apache POI 產生,前端 docx-preview 預覽。
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    // 物件儲存(WP6-T1):MinIO S3 API,pre-signed URL。
    implementation("io.minio:minio:8.5.12")

    // 可觀測性(WP4-T4,ADR-007):Micrometer Observation → OTel → OTLP(Langfuse/Jaeger 皆可收)。
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // 持久化(WP1-T3):PostgreSQL + Flyway。postgres profile 啟用;預設仍為 in-memory。
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // 安全(WP7-T1):OIDC resource server(oidc profile 啟用)。
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.cucumber:cucumber-java:7.20.1")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.20.1")
    testImplementation("org.junit.platform:junit-platform-suite")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
