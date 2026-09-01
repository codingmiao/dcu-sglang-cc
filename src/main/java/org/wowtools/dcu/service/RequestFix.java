package org.wowtools.dcu.service;

import org.wowtools.dcu.pojo.AnthropicMessageRequest;

/**
 * 请求体修复。
 */
public class RequestFix {

    /**
     * 修复 1：sglang 不支持 assistant / user 之外的角色（system、tool 等会直接 400）。
     * 把 messages 中所有非 assistant 的 role 统一改成 user。
     *
     * @return 被改写的消息条数
     */
    public static int fixSystemRole(AnthropicMessageRequest request) {
        if (request.getMessages() == null) {
            return 0;
        }
        int changed = 0;
        for (AnthropicMessageRequest.ConversationMessage message : request.getMessages()) {
            if (!"assistant".equals(message.getRole())) {
                message.setRole("user");
                changed++;
            }
        }
        return changed;
    }
}
