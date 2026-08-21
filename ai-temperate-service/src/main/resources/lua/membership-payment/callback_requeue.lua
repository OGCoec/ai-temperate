local ready_key = KEYS[1]
local processing_key = KEYS[2]
local count = tonumber(ARGV[1])
local ready_at = ARGV[2]
local argument_index = 3
local requeued = 0

for index = 1, count do
    local callback_id = ARGV[argument_index]
    local expected_score = ARGV[argument_index + 1]
    argument_index = argument_index + 2
    local current_score = redis.call('ZSCORE', processing_key, callback_id)
    if current_score and tonumber(current_score) == tonumber(expected_score)
            and redis.call('ZREM', processing_key, callback_id) == 1 then
        redis.call('ZADD', ready_key, ready_at, callback_id)
        requeued = requeued + 1
    end
end
return requeued
