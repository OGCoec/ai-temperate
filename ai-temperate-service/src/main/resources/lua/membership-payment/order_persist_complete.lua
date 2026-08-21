local processing_key = KEYS[1]
local dirty_key = KEYS[2]
local count = tonumber(ARGV[1])
local argument_index = 2
local completed = 0

for index = 1, count do
    local member = ARGV[argument_index]
    local expected_score = ARGV[argument_index + 1]
    local token_version = tonumber(ARGV[argument_index + 2])
    argument_index = argument_index + 3
    local current_score = redis.call('ZSCORE', processing_key, member)
    if current_score and tonumber(current_score) == tonumber(expected_score)
            and redis.call('ZREM', processing_key, member) == 1 then
        redis.call('ZREM', dirty_key, member)
        local snapshot_key = KEYS[index + 2]
        local snapshot_version = tonumber(redis.call('HGET', snapshot_key, 'stateVersion'))
        if snapshot_version == token_version then
            local status = redis.call('HGET', snapshot_key, 'status')
            if status == 'PAID' or status == 'CANCELLED' or status == 'CLOSED' then
                redis.call('UNLINK', snapshot_key)
            end
        end
        completed = completed + 1
    end
end
return completed
