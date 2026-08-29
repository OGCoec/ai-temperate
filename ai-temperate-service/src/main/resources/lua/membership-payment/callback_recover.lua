local ready_key = KEYS[1]
local processing_key = KEYS[2]
local cutoff = ARGV[1]
local maximum = tonumber(ARGV[2])
local ready_at = ARGV[3]

local expired = redis.call(
        'ZRANGEBYSCORE', processing_key, '-inf', cutoff, 'LIMIT', 0, maximum)
if #expired == 0 then
    return 0
end

local zrem = {'ZREM', processing_key}
local zadd = {'ZADD', ready_key}
for _, callback_id in ipairs(expired) do
    zrem[#zrem + 1] = callback_id
    zadd[#zadd + 1] = ready_at
    zadd[#zadd + 1] = callback_id
end
redis.call(unpack(zrem))
redis.call(unpack(zadd))
return #expired
