package com.smartoncall.agent.guard;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具结果截断。
 *
 * <p><b>为什么这是成本优化的关键位置</b>：实测一次 AIOps 运行的 token 消耗中，
 * 输入占 92%、输出仅占 8%，而输入里增长最快的部分是工具返回的内容——
 * 单次调用的输入从 717 一路涨到 6586，涨的基本都是前面几轮工具结果在上下文里的累积。
 *
 * <p>而 Agent 循环的每一轮都会把完整上下文重发一遍，所以<b>一份 8000 字的日志结果，
 * 会在后续每一次调用里重复计费</b>。截断它，省下的是后续所有轮次的钱。
 *
 * <p>截断保留首尾：开头通常是结构化字段（告警名、时间、级别），
 * 结尾往往是结论或异常堆栈的最后几行——中间的大段明细信息密度最低。
 */
public class ToolResultTruncatingInterceptor extends ToolInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(ToolResultTruncatingInterceptor.class);

    private final int maxChars;

    public ToolResultTruncatingInterceptor(int maxChars) {
        this.maxChars = maxChars;
    }

    @Override
    public String getName() {
        return "tool-result-truncator";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        ToolCallResponse response = handler.call(request);
        if (response == null) {
            return null;
        }

        String result = response.getResult();
        if (result == null || result.length() <= maxChars) {
            return response;
        }

        String truncated = truncate(result);
        logger.info("工具 {} 返回 {} 字符，已截断至 {} 字符（后续每轮调用都会重发，截断可重复节省）",
                request.getToolName(), result.length(), truncated.length());

        return ToolCallResponse.builder()
                .toolName(response.getToolName())
                .toolCallId(response.getToolCallId())
                .status(response.getStatus())
                .metadata(response.getMetadata())
                .content(truncated)
                .build();
    }

    private String truncate(String result) {
        int head = maxChars * 3 / 4;
        int tail = maxChars - head;
        String omitted = String.format(
                "\n\n...[中间省略 %d 字符。如需完整内容请缩小查询范围（如缩短时间窗、减少 topK）]...\n\n",
                result.length() - maxChars);
        return result.substring(0, head) + omitted + result.substring(result.length() - tail);
    }
}
