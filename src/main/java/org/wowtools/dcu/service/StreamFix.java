package org.wowtools.dcu.service;

import org.wowtools.dcu.pojo.AnthropicStreamData;

import java.util.HashMap;
import java.util.Map;

/**
 * 流式修复 2：丢弃与所在 content block 类型不匹配的 delta。
 *
 * <p>sglang 输出多个 tool call 时，会把 tool 之间模型吐出的分隔文本（如 "\n"）
 * 以 text_delta 形式混进 tool_use 块的事件流。Anthropic 协议里 tool_use 块只允许
 * input_json_delta、text 块只允许 text_delta，客户端（Claude Code）遇到
 * "tool_use 块收到 text_delta" 会直接抛 "Content block is not a text block"。
 * 本修复按 content_block_start 记录的块类型，把这类不匹配的 delta 拦下。
 *
 * <p>只丢弃单条 delta 事件、从不整块丢弃，因此不会产生不连续的 index。
 */
public class StreamFix {

    private final boolean mismatchedDelta;

    public StreamFix(boolean mismatchedDelta) {
        this.mismatchedDelta = mismatchedDelta;
    }

    /** index -> 块类型（由 content_block_start 记录） */
    private final Map<Integer, String> blockTypes = new HashMap<>();

    /** 累计丢弃的块类型不匹配 delta 数量 */
    private int droppedMismatchedDelta = 0;

    /**
     * 判断某个事件是否应转发给客户端。
     * 在 content_block_start 处记录块类型，在 content_block_delta 处拦截类型不匹配的 delta。
     */
    public boolean shouldForward(AnthropicStreamData event) {
        String type = event.getType();
        if ("content_block_start".equals(type)) {
            if (event.getContentBlock() != null) {
                blockTypes.put(event.getIndex(), event.getContentBlock().getType());
            }
            return true;
        }
        if (!mismatchedDelta
                || !"content_block_delta".equals(type)
                || event.getDelta() == null
                || event.getIndex() == null) {
            return true;
        }
        String blockType = blockTypes.get(event.getIndex());
        if (blockType == null) {
            return true;
        }
        if (isMismatch(blockType, event.getDelta().getType())) {
            droppedMismatchedDelta++;
            return false;
        }
        return true;
    }

    /**
     * delta 类型与所在块类型是否不匹配。
     */
    private static boolean isMismatch(String blockType, String deltaType) {
        if ("text_delta".equals(deltaType)) {
            return !"text".equals(blockType);
        }
        if ("input_json_delta".equals(deltaType)) {
            return !"tool_use".equals(blockType);
        }
        if ("thinking_delta".equals(deltaType) || "signature_delta".equals(deltaType)) {
            return !"thinking".equals(blockType);
        }
        return false;
    }

    public int getDroppedMismatchedDelta() {
        return droppedMismatchedDelta;
    }
}
