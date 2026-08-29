package com.example.temperate.service.user.membership.payment.time;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 该工具是来统一会员订单与支付回调的 UTC 微秒时间边界，避免 Java、Redis 和 PostgreSQL 之间发生隐式精度漂移。
 *
 * <p>PostgreSQL 最终使用 {@code TIMESTAMPTZ(6)}，因此所有进入状态机的时间先向下截断到微秒；
 * 这样内存比较结果与数据库重新读取后的结果保持一致，不依赖驱动执行隐式舍入。</p>
 */
public final class MembershipPaymentTime {

    private static final long MICROS_PER_SECOND = 1_000_000L;

    private MembershipPaymentTime() {
    }

    /** 捕获一次时钟值并规范化为 UTC 微秒，供一个业务动作内复用同一事实时间。 */
    public static OffsetDateTime now(Clock clock) {
        return fromInstant(Objects.requireNonNull(clock, "clock must not be null").instant());
    }

    /** 把任意 Instant 转换为 UTC 六位微秒，供外部 epoch 与内部时钟共用同一精度边界。 */
    public static OffsetDateTime fromInstant(Instant value) {
        return OffsetDateTime.ofInstant(
                Objects.requireNonNull(value, "value must not be null")
                        .truncatedTo(ChronoUnit.MICROS),
                ZoneOffset.UTC);
    }

    /** 将外部或持久化时间转换为同一 UTC 瞬间，并截断 PostgreSQL 无法保存的纳秒尾数。 */
    public static OffsetDateTime normalize(OffsetDateTime value) {
        Objects.requireNonNull(value, "time must not be null");
        return OffsetDateTime.ofInstant(
                value.toInstant().truncatedTo(ChronoUnit.MICROS),
                ZoneOffset.UTC);
    }

    /** 对可空时间执行与必填时间相同的微秒规范化。 */
    public static OffsetDateTime normalizeNullable(OffsetDateTime value) {
        return value == null ? null : normalize(value);
    }

    /** 将业务事实时间编码为 Redis Hash 使用的 epoch-micros。 */
    public static long toEpochMicros(OffsetDateTime value) {
        Instant instant = normalize(value).toInstant();
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), MICROS_PER_SECOND),
                instant.getNano() / 1_000L);
    }

    /** 从 Redis Hash 的 epoch-micros 恢复 UTC 微秒时间。 */
    public static OffsetDateTime fromEpochMicros(long epochMicros) {
        long seconds = Math.floorDiv(epochMicros, MICROS_PER_SECOND);
        long micros = Math.floorMod(epochMicros, MICROS_PER_SECOND);
        return OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(seconds, micros * 1_000L),
                ZoneOffset.UTC);
    }
}
