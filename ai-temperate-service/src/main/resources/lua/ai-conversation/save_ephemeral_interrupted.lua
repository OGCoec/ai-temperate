if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end

local root = 'ephemeral:' .. ARGV[2]
local metaField = root .. ':meta'
local raw = redis.call('HGET', KEYS[1], metaField)
if raw == false then
    return -1
end

local contextMetaRaw = redis.call('HGET', KEYS[1], 'meta')
if contextMetaRaw == false then
    return -1
end

local contextMeta = cjson.decode(contextMetaRaw)
local meta = cjson.decode(raw)
local previousCount = tonumber(meta.assistantChunkCount or 0)
local previousTokens = tonumber(meta.estimatedTokens or 0)
local requestedTokens = tonumber(ARGV[4])
local nextTokens = 0
if ARGV[3] == 'USER_STOP' then
    nextTokens = requestedTokens
end
local updatedTotal = tonumber(contextMeta.estimatedContextTokens or 0)
    - previousTokens + nextTokens
if updatedTotal < 0 then
    return -1
end
local writeCount = tonumber(ARGV[6])
local maximumFields = tonumber(ARGV[7])
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
        ARGV[8 + index])
end

meta.state = 'INTERRUPTED'
meta.interruptionSource = ARGV[3]
meta.assistantChunkCount = writeCount
meta.estimatedTokens = nextTokens
redis.call('HSET', KEYS[1], metaField, cjson.encode(meta))

contextMeta.estimatedContextTokens = updatedTotal
contextMeta.contextRevision = tonumber(contextMeta.contextRevision or 0) + 1
contextMeta.updatedAt = ARGV[5]
redis.call('HSET', KEYS[1], 'meta', cjson.encode(contextMeta))
return 1
