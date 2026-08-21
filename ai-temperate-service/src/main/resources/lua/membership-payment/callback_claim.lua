local ready_key = KEYS[1]
local processing_key = KEYS[2]
local maximum = tonumber(ARGV[1])
local claimed_at = ARGV[2]

local candidates = redis.call('ZRANGE', ready_key, 0, maximum - 1)
local claimed = {}
for _, callback_id in ipairs(candidates) do
    if redis.call('ZREM', ready_key, callback_id) == 1 then
        redis.call('ZADD', processing_key, claimed_at, callback_id)
        table.insert(claimed, callback_id)
    end
end
return claimed
