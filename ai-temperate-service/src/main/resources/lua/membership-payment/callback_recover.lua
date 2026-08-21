local ready_key = KEYS[1]
local processing_key = KEYS[2]
local cutoff = ARGV[1]
local maximum = tonumber(ARGV[2])
local ready_at = ARGV[3]

local expired = redis.call(
        'ZRANGEBYSCORE', processing_key, '-inf', cutoff, 'LIMIT', 0, maximum)
local recovered = 0
for _, callback_id in ipairs(expired) do
    local score = redis.call('ZSCORE', processing_key, callback_id)
    if score and tonumber(score) <= tonumber(cutoff)
            and redis.call('ZREM', processing_key, callback_id) == 1 then
        redis.call('ZADD', ready_key, ready_at, callback_id)
        recovered = recovered + 1
    end
end
return recovered
