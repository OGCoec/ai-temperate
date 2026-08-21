local dirty_key = KEYS[1]
local processing_key = KEYS[2]
local count = tonumber(ARGV[1])
local ready_at = ARGV[2]
local argument_index = 3
local requeued = 0

for index = 1, count do
    local member = ARGV[argument_index]
    local expected_score = ARGV[argument_index + 1]
    argument_index = argument_index + 2
    local current_score = redis.call('ZSCORE', processing_key, member)
    if current_score and tonumber(current_score) == tonumber(expected_score)
            and redis.call('ZREM', processing_key, member) == 1 then
        redis.call('ZADD', dirty_key, ready_at, member)
        requeued = requeued + 1
    end
end
return requeued
