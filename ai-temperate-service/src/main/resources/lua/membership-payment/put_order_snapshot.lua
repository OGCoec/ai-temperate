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
if existed then
    redis.call('UNLINK', snapshot_key)
end
local argument_index = 4
local fields = {}
for index = 1, field_count do
    fields[#fields + 1] = ARGV[argument_index]
    fields[#fields + 1] = ARGV[argument_index + 1]
    argument_index = argument_index + 2
end
if #fields > 0 then
    redis.call('HSET', snapshot_key, unpack(fields))
end
redis.call('PEXPIRE', snapshot_key, ttl_millis)
return existed and 'REPLACED' or 'CREATED'
