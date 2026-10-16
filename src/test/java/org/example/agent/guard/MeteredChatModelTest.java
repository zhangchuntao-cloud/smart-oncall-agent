package org.example.agent.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MeteredChatModel} 的计量与快速失败行为。
 *
 * <p>这个类是整个护栏体系里唯一"必然经过"的埋点——不管哪个 Agent、哪一层子图发起调用，
 * 都要穿过它。所以它的正确性直接决定 token 预算是否真的有效。
 */
class MeteredChatModelTest {

    private static ChatResponse responseWithUsage(Integer promptTokens, Integer completionTokens, Integer total) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("stub-model")
                .usage(new DefaultUsage(promptTokens, completionTokens, total))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), metadata);
    }

    /** 可控的 ChatModel 替身，避免测试依赖真实 LLM */
    private static class StubChatModel implements ChatModel {
        private final ChatResponse response;
        private final RuntimeException failure;

        StubChatModel(ChatResponse response, RuntimeException failure) {
            this.response = response;
            this.failure = failure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (failure != null) {
                return Flux.error(failure);
            }
            return Flux.just(response);
        }
    }

    private static AgentGuardProperties limits() {
        AgentGuardProperties p = new AgentGuardProperties();
        p.setMaxModelCalls(10);
        p.setMaxTotalTokens(1000);
        p.setTimeoutSeconds(60);
        return p;
    }

    @Test
    @DisplayName("把模型返回的 token 用量计入预算")
    void countsTokensFromResponse() {
        AgentBudget budget = new AgentBudget(limits());
        MeteredChatModel model = new MeteredChatModel(
                new StubChatModel(responseWithUsage(100, 50, 150), null), budget);

        model.call(new Prompt("hi"));

        assertThat(budget.snapshot().totalTokens()).isEqualTo(150);
        assertThat(budget.snapshot().modelCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("响应没有 usage 时不计数、不抛异常（供应商不回传用量的兜底）")
    void toleratesMissingUsage() {
        AgentBudget budget = new AgentBudget(limits());
        ChatResponse noUsage = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        MeteredChatModel model = new MeteredChatModel(new StubChatModel(noUsage, null), budget);

        model.call(new Prompt("hi"));

        assertThat(budget.snapshot().totalTokens()).isZero();
        assertThat(budget.isTerminated()).isFalse();
    }

    @Test
    @DisplayName("token 超预算时抛异常终止")
    void terminatesWhenTokenBudgetExceeded() {
        AgentBudget budget = new AgentBudget(limits());
        MeteredChatModel model = new MeteredChatModel(
                new StubChatModel(responseWithUsage(600, 600, 1200), null), budget);

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("token 消耗超过预算");
    }

    @Test
    @DisplayName("不可重试错误：标记终止，且终止原因不含供应商原始报文")
    void nonTransientErrorTerminatesWithSafeMessage() {
        AgentBudget budget = new AgentBudget(limits());
        RuntimeException arrearage = new NonTransientAiException(
                "400 - {\"code\":\"Arrearage\",\"request_id\":\"abc-123\","
                        + "\"message\":\"Access denied\"}");
        MeteredChatModel model = new MeteredChatModel(new StubChatModel(null, arrearage), budget);

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(NonTransientAiException.class);

        assertThat(budget.isTerminated()).isTrue();
        String reason = budget.terminationReason().orElseThrow();
        assertThat(reason).contains("账户余额不足或已欠费");
        // 供应商报文里的 request_id / 文档链接只该进服务端日志，不能出现在给用户的原因里
        assertThat(reason).doesNotContain("request_id").doesNotContain("abc-123");
    }

    @Test
    @DisplayName("不可重试错误后，下一次调用被立即拦下（不再白烧配额）")
    void subsequentCallBlockedAfterNonTransientError() {
        AgentBudget budget = new AgentBudget(limits());
        MeteredChatModel model = new MeteredChatModel(
                new StubChatModel(null, new NonTransientAiException("400 - {\"code\":\"Arrearage\"}")),
                budget);

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(NonTransientAiException.class);

        // 修复前框架会继续重试，每次都记一笔；现在第二次直接拦下
        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(AgentBudgetExceededException.class);
        assertThat(budget.snapshot().modelCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("流式调用同样计入预算")
    void streamAlsoCountsTokens() {
        AgentBudget budget = new AgentBudget(limits());
        MeteredChatModel model = new MeteredChatModel(
                new StubChatModel(responseWithUsage(80, 20, 100), null), budget);

        model.stream(new Prompt("hi")).collectList().block();

        assertThat(budget.snapshot().totalTokens()).isEqualTo(100);
    }
}
