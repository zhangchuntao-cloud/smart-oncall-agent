package com.smartoncall.agent.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentBudget} 的行为测试。
 *
 * <p>这是所有护栏的判断中枢，且是纯逻辑、不依赖 LLM / Milvus / 网络，
 * 所以最适合做单元测试。
 */
class AgentBudgetTest {

    private static AgentGuardProperties limits() {
        AgentGuardProperties p = new AgentGuardProperties();
        p.setMaxModelCalls(3);
        p.setMaxTotalTokens(1000);
        p.setMaxToolCalls(5);
        p.setMaxSameToolRepeats(2);
        p.setTimeoutSeconds(60);
        return p;
    }

    private static Usage usage(int total) {
        return new DefaultUsage(total / 2, total - total / 2, total);
    }

    // ---------------------------------------------------------------- 模型调用次数

    @Test
    @DisplayName("模型调用次数：未达上限放行，达到上限抛异常")
    void modelCallLimit() {
        AgentBudget budget = new AgentBudget(limits());

        budget.beforeModelCall();
        budget.beforeModelCall();
        budget.beforeModelCall();

        assertThatThrownBy(budget::beforeModelCall)
                .isInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("LLM 调用次数超过上限");
    }

    @Test
    @DisplayName("回归：被拒绝的那次调用不应计入画像（曾经差一）")
    void rejectedCallNotCounted() {
        AgentBudget budget = new AgentBudget(limits());

        budget.beforeModelCall();
        budget.beforeModelCall();
        budget.beforeModelCall();
        assertThatThrownBy(budget::beforeModelCall).isInstanceOf(AgentBudgetExceededException.class);

        // 上限是 3，画像里就该是 3 —— 而不是把被拒的第 4 次也算进去
        assertThat(budget.snapshot().modelCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("已被终止后，再次调用立即抛异常")
    void terminatedBudgetRejectsFurtherCalls() {
        AgentBudget budget = new AgentBudget(limits());
        budget.markTerminated("人工终止");

        assertThatThrownBy(budget::beforeModelCall)
                .isInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("人工终止");
    }

    // ---------------------------------------------------------------- token 预算

    @Test
    @DisplayName("token 预算：累计未超限放行，超限抛异常并带上用量")
    void tokenBudget() {
        AgentBudget budget = new AgentBudget(limits());

        budget.recordUsage(usage(400));
        budget.recordUsage(usage(400));
        assertThat(budget.snapshot().totalTokens()).isEqualTo(800);

        assertThatThrownBy(() -> budget.recordUsage(usage(400)))
                .isInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("token 消耗超过预算");
    }

    @Test
    @DisplayName("token 用量为 null 时安全跳过，不误判也不 NPE")
    void nullUsageIsIgnored() {
        AgentBudget budget = new AgentBudget(limits());

        budget.recordUsage(null);

        assertThat(budget.snapshot().totalTokens()).isZero();
        assertThat(budget.isTerminated()).isFalse();
    }

    // ---------------------------------------------------------------- 工具调用

    @Test
    @DisplayName("工具调用总数超限时终止运行")
    void toolCallLimit() {
        AgentBudget budget = new AgentBudget(limits());

        for (int i = 0; i < 5; i++) {
            assertThat(budget.beforeToolCall("toolA", "{\"i\":" + i + "}").blocked()).isFalse();
        }

        assertThatThrownBy(() -> budget.beforeToolCall("toolA", "{\"i\":99}"))
                .isInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("工具调用次数超过上限");
    }

    @Test
    @DisplayName("同一工具+同参数反复调用：超阈值后拦截但不终止运行")
    void repeatedIdenticalToolCallIsBlockedNotTerminated() {
        AgentBudget budget = new AgentBudget(limits());

        assertThat(budget.beforeToolCall("queryLogs", "{\"region\":\"ap-guangzhou\"}").blocked()).isFalse();
        assertThat(budget.beforeToolCall("queryLogs", "{\"region\":\"ap-guangzhou\"}").blocked()).isFalse();

        AgentBudget.ToolDecision third =
                budget.beforeToolCall("queryLogs", "{\"region\":\"ap-guangzhou\"}");

        assertThat(third.blocked()).isTrue();
        assertThat(third.reason()).contains("换一个排查方向");
        // 关键：拦截而不是终止 —— 模型钻牛角尖很常见，直接杀掉整次运行太浪费
        assertThat(budget.isTerminated()).isFalse();
    }

    @Test
    @DisplayName("参数不同则不算重复调用")
    void differentArgumentsAreNotRepeats() {
        AgentBudget budget = new AgentBudget(limits());

        for (int i = 0; i < 5; i++) {
            assertThat(budget.beforeToolCall("queryLogs", "{\"region\":\"r" + i + "\"}").blocked())
                    .as("第 %d 次（参数不同）不应被拦截", i + 1)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("参数里的空白差异不影响重复判定")
    void whitespaceIsNormalizedInSignature() {
        AgentBudget budget = new AgentBudget(limits());

        budget.beforeToolCall("queryLogs", "{ \"region\" : \"gz\" }");
        budget.beforeToolCall("queryLogs", "{\"region\":\"gz\"}");

        assertThat(budget.beforeToolCall("queryLogs", "{\"region\": \"gz\"}").blocked()).isTrue();
    }

    // ---------------------------------------------------------------- 超时

    @Test
    @DisplayName("超过墙钟上限后终止（协作式检查）")
    void timeoutTerminates() throws InterruptedException {
        AgentGuardProperties props = limits();
        props.setTimeoutSeconds(0);
        AgentBudget budget = new AgentBudget(props);

        Thread.sleep(1100);

        assertThatThrownBy(budget::beforeModelCall)
                .isInstanceOf(AgentBudgetExceededException.class)
                .hasMessageContaining("超时");
    }

    // ---------------------------------------------------------------- 画像

    @Test
    @DisplayName("画像如实反映各项消耗与终止原因")
    void snapshotReflectsState() {
        AgentBudget budget = new AgentBudget(limits());
        budget.beforeModelCall();
        budget.recordUsage(usage(250));
        budget.beforeToolCall("t", "{}");

        AgentBudget.Snapshot snapshot = budget.snapshot();
        assertThat(snapshot.modelCalls()).isEqualTo(1);
        assertThat(snapshot.totalTokens()).isEqualTo(250);
        assertThat(snapshot.toolCalls()).isEqualTo(1);
        assertThat(snapshot.terminated()).isFalse();
        assertThat(snapshot.terminationReason()).isNull();
        assertThat(snapshot.toMap()).containsKeys(
                "modelCalls", "totalTokens", "toolCalls", "elapsedSeconds", "terminationReason");
    }
}
