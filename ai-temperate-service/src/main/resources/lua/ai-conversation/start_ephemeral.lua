if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end

local writeCount = tonumber(ARGV[4])
local maximumFields = tonumber(ARGV[5])
if redis.call('HLEN', KEYS[1]) + writeCount + 2 > maximumFields then
    return -1
end

local ordinal = redis.call('HINCRBY', KEYS[1], 'sequence:ephemeral', 1)
local root = 'ephemeral:' .. ordinal
local meta = cjson.encode({
    schemaVersion = 2,
    state = 'STREAMING',
    ordinal = ordinal,
    usagePublicId = ARGV[2],
    createdAt = ARGV[3],
    estimatedTokens = 0
})
redis.call('HSET', KEYS[1], root .. ':meta', meta)

local index = 6
for ignored = 1, writeCount do
    redis.call('HSET', KEYS[1], root .. ':' .. ARGV[index], ARGV[index + 1])
    index = index + 2
end
return ordinal
