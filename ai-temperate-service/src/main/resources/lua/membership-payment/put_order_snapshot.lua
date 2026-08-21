local snapshot_key = KEYS[1]
local incoming_version = tonumber(ARGV[1])
local ttl_millis = tonumber(ARGV[2])
local field_count = tonumber(ARGV[3])

local current_version_text = redis.call('HGET', snapshot_key, 'stateVersion')
local current_version = current_version_text and tonumber(current_version_text) or nil
if current_version and current_version > incoming_version then
    return 'STALE'
end
if current_version and current_version == incoming_version then
    redis.call('PEXPIRE', snapshot_key, ttl_millis)
    return 'UNCHANGED'
end

local existed = redis.call('EXISTS', snapshot_key) == 1
redis.call('UNLINK', snapshot_key)
local argument_index = 4
for index = 1, field_count do
    redis.call('HSET', snapshot_key, ARGV[argument_index], ARGV[argument_index + 1])
    argument_index = argument_index + 2
end
redis.call('PEXPIRE', snapshot_key, ttl_millis)
return existed and 'REPLACED' or 'CREATED'
