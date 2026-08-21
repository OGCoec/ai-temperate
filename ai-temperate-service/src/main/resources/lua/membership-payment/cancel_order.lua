local snapshot_key = KEYS[1]
local marker_key = KEYS[2]
local dirty_key = KEYS[3]
local changed_at = ARGV[1]
local ttl_millis = tonumber(ARGV[2])
local order_id = ARGV[3]

if redis.call('EXISTS', snapshot_key) == 0 then
    return 'MISSING||0'
end
local status = redis.call('HGET', snapshot_key, 'status')
local current_version = tonumber(redis.call('HGET', snapshot_key, 'stateVersion'))
if not status or not current_version then
    return 'NOT_ALLOWED||0'
end
if status == 'CANCELLED' then
    return 'ALREADY_APPLIED|CANCELLED|' .. current_version
end
if redis.call('EXISTS', marker_key) == 1 then
    return 'CALLBACK_IN_PROGRESS|' .. status .. '|' .. current_version
end
if status ~= 'PENDING_PAYMENT' then
    return 'NOT_ALLOWED|' .. status .. '|' .. current_version
end

local next_version = current_version + 1
redis.call('HSET', snapshot_key,
        'status', 'CANCELLED',
        'closingDeadlineAt', '',
        'stateVersion', tostring(next_version),
        'updatedAt', changed_at)
redis.call('PEXPIRE', snapshot_key, ttl_millis)
redis.call('ZADD', dirty_key, changed_at, order_id .. '#' .. next_version)
return 'APPLIED|CANCELLED|' .. next_version
