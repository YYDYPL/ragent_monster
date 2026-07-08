package com.hjs.study.ragent.framework.idempotent;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Objects;

/**
 * 消息消费幂等状态枚举
 *
 * <p>
 * 该枚举用于表示 Redis 中记录的消息消费状态，
 * 是 {@link IdempotentConsumeAspect} 实现幂等控制时的核心状态模型
 * </p>
 *
 * <p>
 * 当前只定义了两个状态：
 * </p>
 * <ul>
 *   <li>消费中：说明某个消费者已经拿到处理权，业务逻辑正在执行</li>
 *   <li>已消费：说明该消息已经处理成功，后续重复投递应当直接跳过</li>
 * </ul>
 */
@RequiredArgsConstructor
public enum IdempotentConsumeStatusEnum {

    /**
     * 消费中
     *
     * <p>
     * 表示某条消息已经抢到幂等 Key，但业务处理尚未完成
     * </p>
     */
    CONSUMING("0"),

    /**
     * 已消费
     *
     * <p>
     * 表示业务逻辑已经成功执行完成，后续重复消息应直接视为已处理
     * </p>
     */
    CONSUMED("1");

    @Getter
    /**
     * 状态编码
     *
     * <p>
     * 使用字符串而不是整数，便于直接存入 Redis 并与 Redis 返回值比较
     * </p>
     */
    private final String code;

    /**
     * 判断当前状态是否表示“重复消费冲突”
     *
     * <p>
     * 这里的命名叫 {@code isError}，从业务语义上看表示：
     * 如果当前 Redis 中已经存在“消费中”状态，
     * 说明这条消息此时不适合再次执行消费逻辑
     * </p>
     *
     * @param consumeStatus Redis 中保存的消费状态
     * @return 如果当前状态是“消费中”返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isError(String consumeStatus) {
        return Objects.equals(CONSUMING.code, consumeStatus);
    }
}
