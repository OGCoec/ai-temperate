local processing_key = KEYS[1]
local snapshot_key = KEYS[2]
local marker_key = KEYS[3]
local provider_result_key = KEYS[4]
local dirty_key = KEYS[5]

local callback_id = ARGV[1]
local expected_score = ARGV[2]
local hard_close_at_micros = ARGV[3]
local hard_close_at_micros_number = tonumber(hard_close_at_micros)
local changed_at_micros = ARGV[4]
local dirty_score_millis = ARGV[5]
local ttl_millis = tonumber(ARGV[6])
local order_id = ARGV[7]

local current_score = redis.call('ZSCORE', processing_key, callback_id)
if not current_score or tonumber(current_score) ~= tonumber(expected_score) then
    return 'NOT_ALLOWED||0'
end
local snapshot = redis.call('HMGET', snapshot_key,
        'status', 'stateVersion', 'closingDeadlineAt')
local status = snapshot[1]
local current_version = tonumber(snapshot[2])
if not snapshot[1] and not snapshot[2] and not snapshot[3] then
    return 'MISSING||0'
end
if not status or not current_version then
    return 'NOT_ALLOWED||0'
end

local marker = redis.call('GET', marker_key)
if marker and marker ~= callback_id then
    return 'CALLBACK_IN_PROGRESS|' .. status .. '|' .. current_version
end

local function release_callback_facts()
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
end

if status == 'CLOSED' or status == 'CANCELLED' then
    release_callback_facts()
    return 'ALREADY_APPLIED|' .. status .. '|' .. current_version
end
if status ~= 'PENDING_PAYMENT' and status ~= 'CLOSING' then
    return 'NOT_ALLOWED|' .. status .. '|' .. current_version
end
if tonumber(changed_at_micros) < hard_close_at_micros_number then
    return 'TOO_EARLY|' .. status .. '|' .. current_version
end

local existing_deadline = snapshot[3]
if status == 'CLOSING'
        and existing_deadline
        and existing_deadline ~= ''
        and tonumber(existing_deadline) ~= hard_close_at_micros_number then
    return 'NOT_ALLOWED|CLOSING|' .. current_version
end

local next_version = current_version + 1
redis.call('HSET', snapshot_key,
        'status', 'CLOSED',
        'closingDeadlineAt', hard_close_at_micros,
        'stateVersion', tostring(next_version),
        'updatedAt', changed_at_micros)
redis.call('PEXPIRE', snapshot_key, ttl_millis)
redis.call('ZADD', dirty_key, dirty_score_millis, order_id .. '#' .. next_version)
release_callback_facts()
return 'APPLIED|CLOSED|' .. next_version
