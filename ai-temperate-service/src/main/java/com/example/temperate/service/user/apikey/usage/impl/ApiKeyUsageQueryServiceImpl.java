package com.example.temperate.service.user.apikey.usage.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.ApiKeyUsageQueryMapper;
import com.example.temperate.mapper.ai.UserApiKeyMapper;
import com.example.temperate.model.ai.entity.ApiKeyUsageRequestRow;
import com.example.temperate.model.ai.entity.ApiKeyUsageSummaryRow;
import com.example.temperate.model.ai.entity.UserApiKey;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageCursorCodec;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageCursorCodec.Cursor;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageModels.Item;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageModels.Page;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageModels.Period;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageModels.Summary;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 该实现是来以会话用户先验证 API Key 所有权，再用服务端 UTC 窗口和摘要执行只读汇总、稳定游标分页，绝不向 Web 层泄露摘要或内部用量 ID。
 */
@Service
public final class ApiKeyUsageQueryServiceImpl implements ApiKeyUsageQueryService {

    private static final int STATUS_DISABLED = 0;
    private static final int STATUS_ENABLED = 1;
    private static final Duration DEFAULT_RANGE = Duration.ofHours(1);
    private static final Duration MAXIMUM_RANGE = Duration.ofDays(31);
    private static final int MAXIMUM_PAGE_SIZE = 100;
    private static final String UNKNOWN_MODEL = "未知模型";
    private static final String UNKNOWN_VENDOR = "unknown";

    private final UserApiKeyMapper keyMapper;
    private final ApiKeyUsageQueryMapper usageMapper;
    private final PublicIdCodec publicIdCodec;
    private final ApiKeyUsageCursorCodec cursorCodec;
    private final Clock clock;

    public ApiKeyUsageQueryServiceImpl(
            UserApiKeyMapper keyMapper,
            ApiKeyUsageQueryMapper usageMapper,
            PublicIdCodec publicIdCodec,
            Clock clock) {
        this.keyMapper = Objects.requireNonNull(keyMapper);
        this.usageMapper = Objects.requireNonNull(usageMapper);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.cursorCodec = new ApiKeyUsageCursorCodec();
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 先完成输入与所有权校验，再取得服务端保存的摘要查询用量；客户端永远不能选择或提交摘要。
     */
    @Override
    @Transactional(readOnly = true)
    public Page query(
            long loginIdentityId,
            byte[] apiKeyId,
            OffsetDateTime from,
            OffsetDateTime to,
            String encodedCursor,
            int pageSize) {
        if (loginIdentityId <= 0 || pageSize < 1 || pageSize > MAXIMUM_PAGE_SIZE) {
            throw invalid("API Key usage query parameters are invalid");
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        TimeRange range = resolveRange(from, to, now);
        Cursor cursor = resolveCursor(encodedCursor, range);
        byte[] internalApiKeyId = requireApiKeyId(apiKeyId);
        ApiKeyUsageSummaryRow summaryRow;
        List<ApiKeyUsageRequestRow> rows;
        try {
            UserApiKey key = keyMapper.findOwnedById(internalApiKeyId, loginIdentityId);
            // 删除状态和跨用户查询返回同一 404，避免通过调用记录接口探测资源是否存在。
            Integer keyStatus = key == null ? null : key.getStatus();
            if (key == null
                    || keyStatus == null
                    || (keyStatus != STATUS_ENABLED && keyStatus != STATUS_DISABLED)) {
                throw notFound();
            }
            byte[] digest = key.getKeyDigest();
            if (digest == null || digest.length != 32) {
                throw dataInvalid("API Key digest is unavailable");
            }

            summaryRow = usageMapper.summarize(digest, range.from(), range.to());
            rows = usageMapper.findPage(
                    digest,
                    range.from(),
                    range.to(),
                    cursor == null ? null : cursor.createdAt(),
                    cursor == null ? null : cursor.usageId(),
                    pageSize + 1);
        } catch (DataAccessException exception) {
            // 数据库异常只转换为稳定 503 分类，禁止将 SQL、摘要绑定值或驱动异常正文返回客户端。
            throw new ApiKeyManagementException(
                    ApiKeyManagementErrorCode.USAGE_QUERY_UNAVAILABLE,
                    "API Key usage query is unavailable",
                    exception);
        }
        if (rows == null) {
            throw dataInvalid("API Key usage page is unavailable");
        }

        boolean hasNext = rows.size() > pageSize;
        List<ApiKeyUsageRequestRow> currentRows = hasNext
                ? rows.subList(0, pageSize)
                : rows;
        List<Item> items = new ArrayList<>(currentRows.size());
        for (ApiKeyUsageRequestRow row : currentRows) {
            items.add(toItem(row));
        }
        String nextCursor = hasNext
                ? encodeNextCursor(currentRows.getLast())
                : null;
        return new Page(
                new Period(instantText(range.from()), instantText(range.to())),
                toSummary(summaryRow),
                items,
                nextCursor);
    }

    private TimeRange resolveRange(
            OffsetDateTime from,
            OffsetDateTime to,
            OffsetDateTime now) {
        if (from == null && to == null) {
            return new TimeRange(now.minus(DEFAULT_RANGE), now);
        }
        if (from == null || to == null) {
            throw invalid("API Key usage from and to must be provided together");
        }
        OffsetDateTime normalizedFrom = from.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime normalizedTo = to.withOffsetSameInstant(ZoneOffset.UTC);
        if (!normalizedTo.isAfter(normalizedFrom)
                || normalizedTo.isAfter(now)
                || Duration.between(normalizedFrom, normalizedTo).compareTo(MAXIMUM_RANGE) > 0) {
            throw invalid("API Key usage time range is invalid");
        }
        return new TimeRange(normalizedFrom, normalizedTo);
    }

    private Cursor resolveCursor(String encoded, TimeRange range) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        Cursor cursor = cursorCodec.decode(encoded);
        // 游标只能在首次响应返回的固定窗口内复用，防止跨时间段拼接分页结果。
        if (cursor.createdAt().isBefore(range.from())
                || !cursor.createdAt().isBefore(range.to())) {
            throw new ApiKeyManagementException(
                    ApiKeyManagementErrorCode.CURSOR_INVALID,
                    "API Key usage cursor is outside the requested range");
        }
        return cursor;
    }

    private static byte[] requireApiKeyId(byte[] id) {
        if (id == null || id.length != 16 || isZero(id)) {
            throw invalid("API Key internal ID is invalid");
        }
        return id.clone();
    }

    private static boolean isZero(byte[] value) {
        int aggregate = 0;
        for (byte current : value) {
            aggregate |= current;
        }
        return aggregate == 0;
    }

    private Summary toSummary(ApiKeyUsageSummaryRow row) {
        ApiKeyUsageSummaryRow value = row == null ? new ApiKeyUsageSummaryRow() : row;
        long prompt = nonNegative(value.getPromptTokens(), "summary prompt tokens");
        long cached = nonNegative(value.getCachedPromptTokens(), "summary cached tokens");
        long uncached = subtract(prompt, cached, "summary uncached tokens");
        return new Summary(
                decimal(nonNegative(value.getRequestCount(), "summary request count")),
                decimal(prompt),
                decimal(cached),
                decimal(uncached),
                decimal(nonNegative(value.getCompletionTokens(), "summary completion tokens")),
                decimal(nonNegative(value.getChargedQuotaMinor(), "summary charged quota")),
                decimal(nonNegative(value.getPendingRequestCount(), "summary pending count")),
                decimal(nonNegative(
                        value.getPendingReservedQuotaMinor(),
                        "summary pending reserved quota")));
    }

    private Item toItem(ApiKeyUsageRequestRow row) {
        if (row == null
                || row.getUsageId() == null
                || row.getUsageId().length != 16
                || row.getAiModelId() == null
                || row.getAiModelId() <= 0
                || row.getCreatedAt() == null) {
            throw dataInvalid("API Key usage row identity is invalid");
        }
        AiModelBillingStatus status = billingStatus(row.getBillingStatus());
        long prompt = nonNegative(row.getPromptTokens(), "prompt tokens");
        long cached = nonNegative(row.getCachedPromptTokens(), "cached prompt tokens");
        String charged = switch (status) {
            case RESERVED -> null;
            case FAILED_REFUNDED, REFUNDED -> "0";
            case SETTLED, RECONCILE_REQUIRED -> decimal(requiredNonNegative(
                    row.getChargedQuotaMinor(), "charged quota"));
        };
        return new Item(
                publicIdCodec.encode(row.getAiModelId()),
                displayText(row.getModelName(), UNKNOWN_MODEL),
                displayText(row.getVendor(), UNKNOWN_VENDOR),
                Boolean.TRUE.equals(row.getStream()),
                status.name(),
                decimal(prompt),
                decimal(cached),
                decimal(subtract(prompt, cached, "uncached prompt tokens")),
                decimal(nonNegative(row.getCompletionTokens(), "completion tokens")),
                charged,
                decimal(nonNegative(row.getReservedQuotaMinor(), "reserved quota")),
                nullableText(row.getFinishReason()),
                nullableText(row.getFailureCode()),
                instantText(row.getCreatedAt()),
                row.getSettledAt() == null ? null : instantText(row.getSettledAt()));
    }

    private String encodeNextCursor(ApiKeyUsageRequestRow row) {
        if (row == null || row.getCreatedAt() == null || row.getUsageId() == null) {
            throw dataInvalid("API Key usage cursor source is invalid");
        }
        return cursorCodec.encode(row.getCreatedAt(), row.getUsageId());
    }

    private static AiModelBillingStatus billingStatus(Integer code) {
        if (code != null) {
            for (AiModelBillingStatus status : AiModelBillingStatus.values()) {
                if (status.code() == code) {
                    return status;
                }
            }
        }
        throw dataInvalid("API Key usage billing status is invalid");
    }

    private static long nonNegative(Long value, String field) {
        long normalized = value == null ? 0L : value;
        if (normalized < 0) {
            throw dataInvalid("API Key usage " + field + " is invalid");
        }
        return normalized;
    }

    private static long requiredNonNegative(Long value, String field) {
        if (value == null) {
            throw dataInvalid("API Key usage " + field + " is unavailable");
        }
        return nonNegative(value, field);
    }

    private static long subtract(long total, long cached, String field) {
        try {
            long result = Math.subtractExact(total, cached);
            if (result < 0) {
                throw dataInvalid("API Key usage " + field + " is invalid");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw dataInvalid("API Key usage " + field + " overflowed");
        }
    }

    private static String displayText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String decimal(long value) {
        return Long.toString(value);
    }

    private static String instantText(OffsetDateTime value) {
        return value.toInstant().toString();
    }

    private static ApiKeyManagementException invalid(String message) {
        return new ApiKeyManagementException(ApiKeyManagementErrorCode.INPUT_INVALID, message);
    }

    private static ApiKeyManagementException notFound() {
        return new ApiKeyManagementException(
                ApiKeyManagementErrorCode.API_KEY_NOT_FOUND,
                "API Key was not found");
    }

    private static ApiKeyManagementException dataInvalid(String message) {
        return new ApiKeyManagementException(
                ApiKeyManagementErrorCode.USAGE_DATA_INVALID,
                message);
    }

    /** 已统一为 UTC 的查询半开区间。 */
    private record TimeRange(OffsetDateTime from, OffsetDateTime to) {
    }
}
