package org.wowtools.dcu.service;

import org.wowtools.dcu.pojo.AnthropicMessageResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 响应体修复（非流式）。
 */
public class ResponseFix {

    /**
     * 修复 2：客户端一次只能处理一个 tool_use。
     * content 中只保留第一个 tool_use，丢弃第 2 个及之后的 tool_use 块（text 等其它块保留）。
     *
     * @return 被丢弃的 tool_use 数量
     */
    public static int fixSingleToolUse(AnthropicMessageResponse res) {
        List<AnthropicMessageResponse.ContentBlock> content = res.getContent();
        if (content == null || content.isEmpty()) {
            return 0;
        }
        boolean seenToolUse = false;
        List<AnthropicMessageResponse.ContentBlock> kept = new ArrayList<>(content.size());
        int dropped = 0;
        for (AnthropicMessageResponse.ContentBlock block : content) {
            if ("tool_use".equals(block.getType())) {
                if (seenToolUse) {
                    dropped++;
                    continue;
                }
                seenToolUse = true;
            }
            kept.add(block);
        }
        if (dropped > 0) {
            res.setContent(kept);
        }
        return dropped;
    }
}
