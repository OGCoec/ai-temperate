local snapshot_key = KEYS[1]
local marker_key = KEYS[2]
local dirty_key = KEYS[3]

local payment_started_in_database = ARGV[1] == '1'
local changed_at_micros = ARGV[2]
local dirty_score_millis = ARGV[3]
local ttl_millis = tonumber(ARGV[4])
local order_id = ARGV[5]

local snapshot = redis.call(
        'HMGET', snapshot_key, 'status', 'stateVersion', 'paymentStartedAt')
local status = snapshot[1]
local current_version = tonumber(snapshot[2])
local payment_started_at = snapshot[3]

if not snapshot[1] and not snapshot[2] and not snapshot[3] then
    return 'MISSING||0'
end
if not status or not current_version then
    return 'NOT_ALLOWED||0'
end
-- 回调入队与 marker 写入由另一条 Lua 原子完成；谁先执行决定后续唯一合法方向。
if redis.call('EXISTS', marker_key) == 1 then
    return 'CALLBACK_IN_PROGRESS|' .. status .. '|' .. current_version
end
if status == 'CLOSED' then
    return 'ALREADY_APPLIED|' .. status .. '|' .. current_version
end
local payment_started_in_redis = payment_started_at and payment_started_at ~= ''
if status == 'CANCELLED'
        and not payment_started_in_database
        and not payment_started_in_redis then
    return 'ALREADY_APPLIED|' .. status .. '|' .. current_version
end
if status ~= 'PENDING_PAYMENT'
        and status ~= 'CLOSING'
        and status ~= 'CANCELLED' then
    return 'NOT_ALLOWED|' .. status .. '|' .. current_version
end

local target_status = 'CANCELLED'
if status == 'CLOSING'
        or status == 'CANCELLED'
        or payment_started_in_database
        or payment_started_in_redis then
    target_status = 'CLOSED'
end

local next_version = current_version + 1
redis.call('HSET', snapshot_key,
        'status', target_status,
        'closingDeadlineAt', '',
        'closingMinimumDeadlineAt', '',
        'stateVersion', tostring(next_version),
        'updatedAt', changed_at_micros)
redis.call('PEXPIRE', snapshot_key, ttl_millis)
redis.call('ZADD', dirty_key, dirty_score_millis, order_id .. '#' .. next_version)
return 'APPLIED|' .. target_status .. '|' .. next_version
