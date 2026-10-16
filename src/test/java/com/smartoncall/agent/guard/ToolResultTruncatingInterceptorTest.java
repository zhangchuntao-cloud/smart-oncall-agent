package com.smartoncall.agent.guard;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolResultTruncatingInterceptor} 的截断行为。
 *
 * <p>背景：实测 token 消耗里输入占 92%，而输入增长最快的是工具结果在上下文中的累积——
 * Agent 循环每轮重发完整上下文，一份超长结果会在后续每次调用里重复计费。
 */
class ToolResultTruncatingInterceptorTest {

    private static ToolCallRequest request() {
        return ToolCallRequest.builder()
                .toolName("queryLogs")
                .toolCallId("call-1")
                .arguments("{\"region\":\"ap-guangzhou\"}")
                .build();
    }

    private static ToolCallHandler handlerReturning(String content) {
        return req -> ToolCallResponse.builder()
                .toolName(req.getToolName())
                .toolCallId(req.getToolCallId())
                .status("SUCCESS")
                .content(content)
                .build();
    }

    @Test
    @DisplayName("结果未超限时原样透传")
    void shortResultPassesThrough() {
        String content = "短结果";
        ToolCallResponse response = new ToolResultTruncatingInterceptor(100)
                .interceptToolCall(request(), handlerReturning(content));

        assertThat(response.getResult()).isEqualTo(content);
    }

    @Test
    @DisplayName("超限时保留首尾、省略中间，并说明省略了多少")
    void longResultIsTruncated() {
        String head = "HEAD-告警名-时间-级别";
        String middle = "x".repeat(5000);
        String tail = "TAIL-根因结论";
        String content = head + middle + tail;

        ToolCallResponse response = new ToolResultTruncatingInterceptor(200)
                .interceptToolCall(request(), handlerReturning(content));

        String result = response.getResult();
        assertThat(result.length()).isLessThan(content.length());
        assertThat(result).startsWith("HEAD");          // 开头保留结构化字段
        assertThat(result).endsWith("TAIL-根因结论");     // 结尾保留结论
        assertThat(result).contains("中间省略");
    }

    @Test
    @DisplayName("截断后不丢失工具名、调用 ID 与状态")
    void metadataIsPreserved() {
        ToolCallResponse response = new ToolResultTruncatingInterceptor(100)
                .interceptToolCall(request(), handlerReturning("y".repeat(1000)));

        assertThat(response.getToolName()).isEqualTo("queryLogs");
        assertThat(response.getToolCallId()).isEqualTo("call-1");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("null 结果不抛异常")
    void nullResultIsSafe() {
        ToolCallResponse response = new ToolResultTruncatingInterceptor(100)
                .interceptToolCall(request(), handlerReturning(null));

        assertThat(response.getResult()).isNull();
    }
}
