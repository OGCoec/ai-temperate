local processing_key = KEYS[1]
local callback_data_key = KEYS[2]
local marker_key = KEYS[3]
local provider_result_key = KEYS[4]
local callback_id = ARGV[1]
local expected_score = ARGV[2]
local provider_result_action = ARGV[3]
local has_order = ARGV[4] == '1'

local current_score = redis.call('ZSCORE', processing_key, callback_id)
if not current_score or tonumber(current_score) ~= tonumber(expected_score)
        or redis.call('ZREM', processing_key, callback_id) ~= 1 then
    return 0
end

if has_order then
    if redis.call('GET', marker_key) == callback_id then
        redis.call('UNLINK', marker_key)
    end
    -- 只有当前模拟结果仍属于本 callback 时才收敛，避免旧 Worker 改写其他并发胜者。
    if redis.call('HGET', provider_result_key, 'callbackId') == callback_id then
        if provider_result_action == 'REMOVE' then
            redis.call('UNLINK', provider_result_key)
        elseif provider_result_action == 'RESET_UNPAID' then
            -- 无效成功通知不能留下 PAID，也不能删除成 UNKNOWN；明确 UNPAID 才允许软关单继续安全收敛。
            redis.call('HSET', provider_result_key,
                    'status', 'UNPAID',
                    'callbackId', '',
                    'providerTradeNo', '',
                    'payType', '',
                    'paidAmountYuan', '')
        end
    end
end
redis.call('UNLINK', callback_data_key)
return 1
