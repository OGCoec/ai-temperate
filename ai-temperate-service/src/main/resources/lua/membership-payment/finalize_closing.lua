local snapshot_key = KEYS[1]
local marker_key = KEYS[2]
local provider_result_key = KEYS[3]
local dirty_key = KEYS[4]
local changed_at = tonumber(ARGV[1])
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
if status == 'CLOSED' then
    return 'ALREADY_APPLIED|CLOSED|' .. current_version
end
if status ~= 'CLOSING' then
    return 'NOT_ALLOWED|' .. status .. '|' .. current_version
end
local deadline = tonumber(redis.call('HGET', snapshot_key, 'closingDeadlineAt'))
if not deadline or deadline > changed_at then
    return 'TOO_EARLY|CLOSING|' .. current_version
end
if redis.call('EXISTS', marker_key) == 1 then
    return 'CALLBACK_IN_PROGRESS|CLOSING|' .. current_version
end
local provider_status = redis.call('HGET', provider_result_key, 'status')
if provider_status ~= 'UNPAID' then
    return 'PROVIDER_STATUS_UNSAFE|CLOSING|' .. current_version
end

local next_version = current_version + 1
redis.call('HSET', snapshot_key,
        'status', 'CLOSED',
        'stateVersion', tostring(next_version),
        'updatedAt', tostring(changed_at))
redis.call('PEXPIRE', snapshot_key, ttl_millis)
redis.call('ZADD', dirty_key, changed_at, order_id .. '#' .. next_version)
return 'APPLIED|CLOSED|' .. next_version
