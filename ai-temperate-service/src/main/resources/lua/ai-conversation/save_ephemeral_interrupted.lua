if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end

local root = 'ephemeral:' .. ARGV[2]
local metaField = root .. ':meta'
local raw = redis.call('HGET', KEYS[1], metaField)
if raw == false then
    return -1
end

local meta = cjson.decode(raw)
local previousCount = tonumber(meta.assistantChunkCount or 0)
local writeCount = tonumber(ARGV[4])
local maximumFields = tonumber(ARGV[5])
local missing = 0
for index = 0, writeCount - 1 do
    local field = root .. ':assistant:' .. string.format('%08d', index)
    if redis.call('HEXISTS', KEYS[1], field) == 0 then
        missing = missing + 1
    end
end
if redis.call('HLEN', KEYS[1]) + missing > maximumFields then
    return -1
end

for index = 0, previousCount - 1 do
    redis.call('HDEL', KEYS[1], root .. ':assistant:' .. string.format('%08d', index))
end
for index = 0, writeCount - 1 do
    redis.call('HSET', KEYS[1],
        root .. ':assistant:' .. string.format('%08d', index),
        ARGV[6 + index])
end

meta.state = 'INTERRUPTED'
meta.interruptionSource = ARGV[3]
meta.assistantChunkCount = writeCount
redis.call('HSET', KEYS[1], metaField, cjson.encode(meta))
return 1
