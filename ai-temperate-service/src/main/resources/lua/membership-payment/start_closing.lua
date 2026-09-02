local snapshot_key = KEYS[1]
local dirty_key = KEYS[2]
local closing_deadline_at_micros = ARGV[1]
local changed_at_micros = ARGV[2]
local dirty_score_millis = ARGV[3]
local ttl_millis = tonumber(ARGV[4])
local order_id = ARGV[5]
local minimum_closing_deadline_at_micros = tonumber(ARGV[6])

local snapshot = redis.call('HMGET', snapshot_key, 'status', 'stateVersion')
local status = snapshot[1]
local current_version = tonumber(snapshot[2])
if not snapshot[1] and not snapshot[2] then
    return 'MISSING||0'
end
if not status or not current_version then
    return 'NOT_ALLOWED||0'
end
if status == 'CLOSING' then
    return 'ALREADY_APPLIED|CLOSING|' .. current_version
end
if status ~= 'PENDING_PAYMENT' then
    return 'NOT_ALLOWED|' .. status .. '|' .. current_version
end
-- 外部支付人工取消也必须沿用原订单边界；调用方传入过早截止时间时拒绝写入 CLOSING。
if minimum_closing_deadline_at_micros
        and tonumber(closing_deadline_at_micros) < minimum_closing_deadline_at_micros then
    return 'TOO_EARLY|PENDING_PAYMENT|' .. current_version
end

local next_version = current_version + 1
redis.call('HSET', snapshot_key,
        'status', 'CLOSING',
        'closingDeadlineAt', closing_deadline_at_micros,
        'closingMinimumDeadlineAt', minimum_closing_deadline_at_micros or '',
        'stateVersion', tostring(next_version),
        'updatedAt', changed_at_micros)
redis.call('PEXPIRE', snapshot_key, ttl_millis)
redis.call('ZADD', dirty_key, dirty_score_millis, order_id .. '#' .. next_version)
return 'APPLIED|CLOSING|' .. next_version
