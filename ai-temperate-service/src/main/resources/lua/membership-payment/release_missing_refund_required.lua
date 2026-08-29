local processing_key = KEYS[1]
local marker_key = KEYS[2]
local provider_result_key = KEYS[3]

local callback_id = ARGV[1]
local expected_score = ARGV[2]

local current_score = redis.call('ZSCORE', processing_key, callback_id)
if not current_score or tonumber(current_score) ~= tonumber(expected_score) then
    return 'CLAIM_MISMATCH'
end

local marker = redis.call('GET', marker_key)
if marker and marker ~= callback_id then
    return 'CALLBACK_CONFLICT'
end
local provider_callback_id = redis.call('HGET', provider_result_key, 'callbackId')
if provider_callback_id
        and provider_callback_id ~= ''
        and provider_callback_id ~= callback_id then
    return 'CALLBACK_CONFLICT'
end

local released = false
if marker == callback_id then
    redis.call('UNLINK', marker_key)
    released = true
end
if provider_callback_id == callback_id then
    redis.call('HSET', provider_result_key,
            'status', 'UNPAID',
            'callbackId', '',
            'providerTradeNo', '',
            'payType', '',
            'paidAmountYuan', '')
    released = true
end

if released then
    return 'RELEASED'
end
return 'ALREADY_RELEASED'
