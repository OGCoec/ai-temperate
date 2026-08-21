local ttl_millis = tonumber(ARGV[1])
local count = tonumber(ARGV[2])
local argument_index = 3
local processed = 0

for index = 1, count do
    local snapshot_key = KEYS[index]
    local incoming_version = tonumber(ARGV[argument_index])
    local field_count = tonumber(ARGV[argument_index + 1])
    argument_index = argument_index + 2
    local current_version_text = redis.call('HGET', snapshot_key, 'stateVersion')
    local current_version = current_version_text and tonumber(current_version_text) or nil
    if not current_version or current_version < incoming_version then
        redis.call('UNLINK', snapshot_key)
        for field_index = 1, field_count do
            redis.call('HSET', snapshot_key,
                    ARGV[argument_index], ARGV[argument_index + 1])
            argument_index = argument_index + 2
        end
    else
        argument_index = argument_index + field_count * 2
    end
    redis.call('PEXPIRE', snapshot_key, ttl_millis)
    processed = processed + 1
end
return processed
