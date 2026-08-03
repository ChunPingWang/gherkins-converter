package com.example.llmagent.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.llmagent.application.port.out.AgentProfileStore;
import com.example.llmagent.domain.agent.AgentProfile;
import com.example.llmagent.domain.agent.PromptTemplate;

/**
 * Agent Profile 應用服務(WP2-T3/T4,ADR-006)。
 * 建立時 version=1;更新一律 append 新版本;prompt 套用 {@link PromptTemplate} 變數。
 */
@Service
public class AgentProfileService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentProfileService.class);

    private final AgentProfileStore store;
    private final com.example.llmagent.adapter.out.langfuse.LangfusePromptSource langfuse;

    public AgentProfileService(AgentProfileStore store,
                               @org.springframework.lang.Nullable
                               com.example.llmagent.adapter.out.langfuse.LangfusePromptSource langfuse) {
        this.store = store;
        this.langfuse = langfuse;
    }

    public AgentProfile create(String name, String description, String systemPrompt,
                               String defaultModelId, Double temperature, List<String> tools) {
        AgentProfile p = new AgentProfile(
                UUID.randomUUID().toString(), 1, name, description, systemPrompt,
                defaultModelId, temperature, tools == null ? List.of() : tools,
                true, Instant.now());
        store.save(p);
        return p;
    }

    /** 更新 → append 新版本(舊版不動,可追溯)。 */
    public AgentProfile update(String profileId, String name, String description, String systemPrompt,
                               String defaultModelId, Double temperature, List<String> tools) {
        AgentProfile latest = store.findLatest(profileId)
                .orElseThrow(() -> new IllegalArgumentException("agent profile not found: " + profileId));
        AgentProfile next = latest.nextVersion(
                name != null && !name.isBlank() ? name : latest.name(),
                description != null ? description : latest.description(),
                systemPrompt != null && !systemPrompt.isBlank() ? systemPrompt : latest.systemPrompt(),
                defaultModelId != null && !defaultModelId.isBlank() ? defaultModelId : latest.defaultModelId(),
                temperature != null ? temperature : latest.temperature(),
                tools != null ? tools : latest.tools(),
                Instant.now());
        store.save(next);
        return next;
    }

    public List<AgentProfile> listLatest() {
        return store.findAllLatest();
    }

    public Optional<AgentProfile> findLatest(String profileId) {
        return store.findLatest(profileId);
    }

    public List<AgentProfile> versions(String profileId) {
        return store.findVersions(profileId);
    }

    /** 還原至指定版本:以該版全部內容 append 為最新版本(版本鏈不回退,還原本身可再被還原)。 */
    public AgentProfile restore(String profileId, int version) {
        AgentProfile target = store.findVersions(profileId).stream()
                .filter(p -> p.version() == version)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "version not found: " + profileId + " v" + version));
        return update(profileId, target.name(), target.description(), target.systemPrompt(),
                target.defaultModelId(), target.temperature(), target.tools());
    }

    /**
     * 解析 Profile 的 system prompt(套用範本變數)。
     * Prompt 主版本優先自 Langfuse Prompt Management 取用(以 Profile 名稱對應,ADR-006/WP4-T5),
     * 未設定或取用失敗時 fallback 至 DB 版本。
     *
     * @throws PromptTemplate.MissingVariableException 缺變數時
     */
    public String renderPrompt(String profileId, Map<String, String> variables) {
        AgentProfile p = store.findLatest(profileId)
                .orElseThrow(() -> new IllegalArgumentException("agent profile not found: " + profileId));
        String template = p.systemPrompt();
        if (langfuse != null && langfuse.enabled()) {
            var remote = langfuse.fetch(p.name());
            if (remote.isPresent()) {
                template = remote.get().prompt();
                log.info("prompt 來源=Langfuse name={} version={}", p.name(), remote.get().version());
            } else {
                log.info("prompt 來源=DB(Langfuse fallback)name={} version={}", p.name(), p.version());
            }
        }
        return PromptTemplate.render(template, variables == null ? Map.of() : variables);
    }
}
