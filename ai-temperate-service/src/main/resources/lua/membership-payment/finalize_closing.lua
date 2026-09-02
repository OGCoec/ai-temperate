local snapshot_key = KEYS[1]
local marker_key = KEYS[2]
local provider_result_key = KEYS[3]
local dirty_key = KEYS[4]
local changed_at_micros = ARGV[1]
local dirty_score_millis = ARGV[2]
local ttl_millis = tonumber(ARGV[3])
local order_id = ARGV[4]

local snapshot = redis.call('HMGET', snapshot_key,
        'status', 'stateVersion', 'closingDeadlineAt', 'closingMinimumDeadlineAt')
local status = snapshot[1]
local current_version = tonumber(snapshot[2])
if not snapshot[1] and not snapshot[2] and not snapshot[3] and not snapshot[4] then
    return 'MISSING||0'
end
if not status or not current_version then
    return 'NOT_ALLOWED||0'
end
if status == 'CLOSED' then
    return 'ALREADY_APPLIED|CLOSED|' .. current_version
end
if status ~= 'CLOSING' then
    return 'NOT_ALLOWED|' .. status .. '|' .. current_version
end
local deadline = tonumber(snapshot[3])
local minimum_deadline = tonumber(snapshot[4])
if minimum_deadline and (not deadline or minimum_deadline > deadline) then
    deadline = minimum_deadline
end
if not deadline or deadline > tonumber(changed_at_micros) then
    return 'TOO_EARLY|CLOSING|' .. current_version
end
if redis.call('EXISTS', marker_key) == 1 then
    return 'CALLBACK_IN_PROGRESS|CLOSING|' .. current_version
end
-- 外部 Provider 的观测状态由调用方显式传入；旧本地模拟路径没有第五参数时仍读取原 Provider Hash。
local provider_status = ARGV[5]
if not provider_status or provider_status == '' then
    provider_status = redis.call('HGET', provider_result_key, 'status')
end

-- 来源只改变允许的 Provider 状态白名单；状态、deadline 和 callback marker 的 CAS 前置条件完全共享。
local finalization_source = ARGV[6]
if not finalization_source or finalization_source == '' then
    finalization_source = 'PROVIDER_CONFIRMED'
end

local provider_confirmed_safe = provider_status == 'UNPAID'
        or provider_status == 'CLOSED'
        or provider_status == 'EXPIRED'
        or provider_status == 'FAILED'
        or provider_status == 'REFUNDED'
local timeout_unconfirmed_safe = provider_status == 'PENDING'
        or provider_status == 'UNKNOWN'

if finalization_source == 'PROVIDER_CONFIRMED' then
    if not provider_confirmed_safe then
        return 'PROVIDER_STATUS_UNSAFE|CLOSING|' .. current_version
    end
elseif finalization_source == 'TIMEOUT_UNCONFIRMED' then
    if not timeout_unconfirmed_safe then
        return 'PROVIDER_STATUS_UNSAFE|CLOSING|' .. current_version
    end
else
    return 'NOT_ALLOWED|CLOSING|' .. current_version
end

local next_version = current_version + 1
redis.call('HSET', snapshot_key,
        'status', 'CLOSED',
        'stateVersion', tostring(next_version),
        'updatedAt', changed_at_micros)
redis.call('PEXPIRE', snapshot_key, ttl_millis)
redis.call('ZADD', dirty_key, dirty_score_millis, order_id .. '#' .. next_version)
return 'APPLIED|CLOSED|' .. next_version
