local processing_key = KEYS[1]
local count = tonumber(ARGV[1])
local argument_index = 2
local completed = 0

for index = 1, count do
    local callback_id = ARGV[argument_index]
    local expected_score = ARGV[argument_index + 1]
    local provider_result_action = ARGV[argument_index + 2]
    argument_index = argument_index + 3
    local current_score = redis.call('ZSCORE', processing_key, callback_id)
    if current_score and tonumber(current_score) == tonumber(expected_score)
            and redis.call('ZREM', processing_key, callback_id) == 1 then
        local key_offset = (index - 1) * 3
        local callback_data_key = KEYS[key_offset + 2]
        local marker_key = KEYS[key_offset + 3]
        local provider_result_key = KEYS[key_offset + 4]
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
        redis.call('UNLINK', callback_data_key)
        completed = completed + 1
    end
end
return completed
