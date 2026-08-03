package com.example.llmagent.adapter.in.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.llmagent.application.AgentRouterService;
import com.example.llmagent.application.AgentRouterService.RouteDecision;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import reactor.core.publisher.Mono;

/**
 * Agent 自動路由端點(層次一)。前端 Agent 選「自動」時,送出前先呼叫本端點取得決策;
 * 決策僅為建議,實際送出仍走既有 /messages 流程並記錄 agentProfileId(追溯不變)。
 * 契約見 specs/openapi.yaml。
 */
@RestController
@RequestMapping("/api/route")
public class RouteController {

    private final AgentRouterService router;

    public RouteController(AgentRouterService router) {
        this.router = router;
    }

    public record RouteRequest(@NotBlank String content) {
    }

    @PostMapping
    public Mono<RouteDecision> route(@Valid @RequestBody RouteRequest req) {
        return router.route(req.content());
    }
}
