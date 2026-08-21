local dirty_key = KEYS[1]
local processing_key = KEYS[2]
local maximum = tonumber(ARGV[1])
local claimed_at = ARGV[2]

local candidates = redis.call('ZRANGE', dirty_key, 0, maximum - 1)
local claimed = {}
for _, member in ipairs(candidates) do
    if redis.call('ZREM', dirty_key, member) == 1 then
        redis.call('ZADD', processing_key, claimed_at, member)
        table.insert(claimed, member)
    end
end
return claimed
