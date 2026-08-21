local snapshot_key = KEYS[1]
local dirty_key = KEYS[2]
local closing_deadline_at = ARGV[1]
local changed_at = ARGV[2]
local ttl_millis = tonumber(ARGV[3])
local order_id = ARGV[4]

if redis.call('EXISTS', snapshot_key) == 0 then
    return 'MISSING||0'
end
local status = redis.call('HGET', snapshot_key, 'status')
local current_version = tonumber(redis.call('HGET', snapshot_key, 'stateVersion'))
if not status or not current_version then
    return 'NOT_ALLOWED||0'
end
if status == 'CLOSING' then
    return 'ALREADY_APPLIED|CLOSING|' .. current_version
end
if status ~= 'PENDING_PAYMENT' then
    return 'NOT_ALLOWED|' .. status .. '|' .. current_version
end

local next_version = current_version + 1
redis.call('HSET', snapshot_key,
        'status', 'CLOSING',
        'closingDeadlineAt', closing_deadline_at,
        'stateVersion', tostring(next_version),
        'updatedAt', changed_at)
redis.call('PEXPIRE', snapshot_key, ttl_millis)
redis.call('ZADD', dirty_key, changed_at, order_id .. '#' .. next_version)
return 'APPLIED|CLOSING|' .. next_version
