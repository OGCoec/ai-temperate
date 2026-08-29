local dirty_key = KEYS[1]
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
local zadd = {'ZADD', dirty_key}
for _, member in ipairs(expired) do
    zrem[#zrem + 1] = member
    zadd[#zadd + 1] = ready_at
    zadd[#zadd + 1] = member
end
redis.call(unpack(zrem))
redis.call(unpack(zadd))
return #expired
