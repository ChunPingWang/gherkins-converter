package com.example.llmagent.adapter.in.web;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.llmagent.application.PublishService;
import com.example.llmagent.application.PublishService.PublishResult;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 產出物發布端點:對話產出依 GitFlow 寫入 Git 託管服務(場景開 Issue、
 * feature 分支提交程式碼、PR 以 Closes 連結 Issues)。blocking HTTP,移至 boundedElastic。
 * 契約見 specs/openapi.yaml。
 */
@RestController
@RequestMapping("/api/conversations/{conversationId}/publish")
public class PublishController {

    private final PublishService publish;

    public PublishController(PublishService publish) {
        this.publish = publish;
    }

    @PostMapping
    public Mono<PublishResult> publish(@PathVariable String conversationId) {
        return Mono.fromCallable(() -> publish.publish(conversationId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
