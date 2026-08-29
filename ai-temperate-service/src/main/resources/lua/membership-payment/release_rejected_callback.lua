local processing_key = KEYS[1]
local marker_key = KEYS[2]
local provider_result_key = KEYS[3]

local callback_id = ARGV[1]
local expected_score = ARGV[2]

local current_score = redis.call('ZSCORE', processing_key, callback_id)
if not current_score or tonumber(current_score) ~= tonumber(expected_score) then
    return 0
end

local marker = redis.call('GET', marker_key)
if marker and marker ~= callback_id then
    return 0
end
if marker == callback_id then
    redis.call('UNLINK', marker_key)
end
if redis.call('HGET', provider_result_key, 'callbackId') == callback_id then
    redis.call('HSET', provider_result_key,
            'status', 'UNPAID',
            'callbackId', '',
            'providerTradeNo', '',
            'payType', '',
            'paidAmountYuan', '')
end
return 1
