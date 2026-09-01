package org.wowtools.dcu.service;

import org.wowtools.dcu.pojo.AnthropicStreamData;

import java.util.HashSet;
import java.util.Set;

/**
 * 流式修复 2：客户端一次只能处理一个 tool_use。
 *
 * <p>sglang 把每个 tool_use 拆成独立 index 的 content_block 事件流
 * （content_block_start / content_block_delta / content_block_stop）。
 * 本过滤器跟踪已出现的 tool_use 块：第一个放行，第 2 个及之后的整块丢弃
 * （其 start / delta / stop 事件都不再转发给客户端）。
 *
 * <p>典型场景 text(0) + tool_use(1) + tool_use(2)：丢弃 index 2 后，
 * 客户端仍收到连续的 0、1，无感知。
 */
public class StreamToolUseFilter {

    /** 已判定为丢弃的 index 集合 */
    private final Set<Integer> droppedIndices = new HashSet<>();

    /** 已放行的 tool_use 块数量 */
    private int keptToolUse = 0;

    /** 累计丢弃的 tool_use 块数量 */
    private int droppedToolUse = 0;

    /**
     * 判断某个事件是否应转发给客户端。
     * 在 content_block_start 处识别 tool_use 块并决定是否整块丢弃。
     */
    public boolean shouldForward(AnthropicStreamData event) {
        String type = event.getType();
        if ("content_block_start".equals(type)) {
            AnthropicStreamData.ContentBlockStartData block = event.getContentBlock();
            if (block != null && "tool_use".equals(block.getType())) {
                keptToolUse++;
                if (keptToolUse > 1) {
                    // 第 2 个及之后的 tool_use：标记该 index 整块丢弃
                    droppedIndices.add(event.getIndex());
                    droppedToolUse++;
                    return false;
                }
            }
        }
        // 属于被丢弃 index 的事件（delta / stop）一律不转发
        return !droppedIndices.contains(event.getIndex());
    }

    public int getDroppedToolUse() {
        return droppedToolUse;
    }
}
