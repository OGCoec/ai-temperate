local dirty_key = KEYS[1]
local processing_key = KEYS[2]
local cutoff = ARGV[1]
local maximum = tonumber(ARGV[2])
local ready_at = ARGV[3]

local expired = redis.call(
        'ZRANGEBYSCORE', processing_key, '-inf', cutoff, 'LIMIT', 0, maximum)
local recovered = 0
for _, member in ipairs(expired) do
    local score = redis.call('ZSCORE', processing_key, member)
    if score and tonumber(score) <= tonumber(cutoff)
            and redis.call('ZREM', processing_key, member) == 1 then
        redis.call('ZADD', dirty_key, ready_at, member)
        recovered = recovered + 1
    end
end
return recovered
