package com.oak.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class OpenRouterClient {

    private final WebClient webClient;
    private final String defaultModel;

    public OpenRouterClient(
            @Value("${openrouter.baseUrl:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${openrouter.apiKey:}") String apiKey,
            @Value("${openrouter.model:openrouter/auto}") String defaultModel
    ) {
        this.defaultModel = defaultModel;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                // Optional but recommended by OpenRouter for analytics/rate treatment
                .defaultHeader("HTTP-Referer", "https://localhost")
                .defaultHeader("X-Title", "OakIndex")
                .build();
    }

    public Mono<ChatCompletionResponse> chat(List<ChatMessage> messages) {
        return chat(messages, null, null, null);
    }

    public Mono<ChatCompletionResponse> chat(
            List<ChatMessage> messages,
            String model,
            Double temperature,
            Integer maxTokens
    ) {
        ChatCompletionRequest req = new ChatCompletionRequest(
                model != null && !model.isBlank() ? model : this.defaultModel,
                messages,
                temperature,
                maxTokens
        );

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(req))
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class);
    }

    // DTOs

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatMessage(
            String role,   // "system" | "user" | "assistant"
            String content
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionResponse(
            String id,
            String object,
            Long created,
            String model,
            List<Choice> choices,
            Usage usage
    ) {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Choice(
                int index,
                ChatMessage message,
                @JsonProperty("finish_reason") String finishReason
        ) {}

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Usage(
                @JsonProperty("prompt_tokens") Integer promptTokens,
                @JsonProperty("completion_tokens") Integer completionTokens,
                @JsonProperty("total_tokens") Integer totalTokens
        ) {}
    }
}

