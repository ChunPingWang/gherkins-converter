package com.example.llmagent.adapter.in.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.llmagent.application.AgentProfileService;
import com.example.llmagent.domain.agent.AgentProfile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Agent Profile 端點(WP2-T3,openapi:/api/agent-profiles)。
 * PUT 一律產生新版本(append-only);/versions 回傳完整歷史。
 */
@RestController
@RequestMapping("/api/agent-profiles")
public class AgentProfileController {

    private final AgentProfileService service;

    public AgentProfileController(AgentProfileService service) {
        this.service = service;
    }

    public record AgentProfileCreate(
            @NotBlank String name,
            String description,
            @NotBlank String systemPrompt,
            String defaultModelId,
            Double temperature,
            List<String> tools) {
    }

    @GetMapping
    public List<AgentProfile> list() {
        return service.listLatest();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentProfile create(@Valid @RequestBody AgentProfileCreate req) {
        return service.create(req.name(), req.description(), req.systemPrompt(),
                req.defaultModelId(), req.temperature(), req.tools());
    }

    @PutMapping("/{profileId}")
    public AgentProfile update(@PathVariable String profileId, @RequestBody AgentProfileCreate req) {
        return service.update(profileId, req.name(), req.description(), req.systemPrompt(),
                req.defaultModelId(), req.temperature(), req.tools());
    }

    @GetMapping("/{profileId}/versions")
    public List<AgentProfile> versions(@PathVariable String profileId) {
        return service.versions(profileId);
    }

    /** 還原至指定版本(以該版內容 append 為新版本,版本鏈不回退)。 */
    @PostMapping("/{profileId}/versions/{version}/restore")
    public AgentProfile restore(@PathVariable String profileId, @PathVariable int version) {
        return service.restore(profileId, version);
    }
}
