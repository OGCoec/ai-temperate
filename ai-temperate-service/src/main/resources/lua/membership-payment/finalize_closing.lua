local snapshot_key = KEYS[1]
local marker_key = KEYS[2]
local provider_result_key = KEYS[3]
local dirty_key = KEYS[4]
local changed_at_micros = ARGV[1]
local dirty_score_millis = ARGV[2]
local ttl_millis = tonumber(ARGV[3])
local order_id = ARGV[4]

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
if status == 'CLOSED' then
    return 'ALREADY_APPLIED|CLOSED|' .. current_version
end
if status ~= 'CLOSING' then
    return 'NOT_ALLOWED|' .. status .. '|' .. current_version
end
local deadline = tonumber(snapshot[3])
if not deadline or deadline > tonumber(changed_at_micros) then
    return 'TOO_EARLY|CLOSING|' .. current_version
end
if redis.call('EXISTS', marker_key) == 1 then
    return 'CALLBACK_IN_PROGRESS|CLOSING|' .. current_version
end
-- 外部 Provider 的已核验终态由调用方显式传入；旧本地模拟路径没有第四参数时仍读取原 Provider Hash。
local provider_status = ARGV[5]
if not provider_status or provider_status == '' then
    provider_status = redis.call('HGET', provider_result_key, 'status')
end
if provider_status ~= 'UNPAID'
        and provider_status ~= 'CLOSED'
        and provider_status ~= 'EXPIRED'
        and provider_status ~= 'FAILED'
        and provider_status ~= 'REFUNDED' then
    return 'PROVIDER_STATUS_UNSAFE|CLOSING|' .. current_version
end

local next_version = current_version + 1
redis.call('HSET', snapshot_key,
        'status', 'CLOSED',
        'stateVersion', tostring(next_version),
        'updatedAt', changed_at_micros)
redis.call('PEXPIRE', snapshot_key, ttl_millis)
redis.call('ZADD', dirty_key, dirty_score_millis, order_id .. '#' .. next_version)
return 'APPLIED|CLOSED|' .. next_version
