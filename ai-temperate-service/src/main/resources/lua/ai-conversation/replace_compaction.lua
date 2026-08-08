if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end

local currentMetaRaw = redis.call('HGET', KEYS[1], 'meta')
if currentMetaRaw == false then
    return -1
end
local currentMeta = cjson.decode(currentMetaRaw)
if tonumber(currentMeta.contextRevision or 0) ~= tonumber(ARGV[2]) then
    return 2
end

local maximumFields = tonumber(ARGV[4])
local deleteCount = tonumber(ARGV[5])
local index = 6
local removableCount = 0
for offset = 0, deleteCount - 1 do
    if redis.call('HEXISTS', KEYS[1], ARGV[index + offset]) == 1 then
        removableCount = removableCount + 1
    end
end

local writeCountIndex = index + deleteCount
local writeCount = tonumber(ARGV[writeCountIndex])
if redis.call('HLEN', KEYS[1]) - removableCount + writeCount > maximumFields then
    return -1
end

redis.call('HSET', KEYS[1], 'meta', ARGV[3])
for ignored = 1, deleteCount do
    redis.call('HDEL', KEYS[1], ARGV[index])
    index = index + 1
end

index = index + 1
for ignored = 1, writeCount do
    redis.call('HSET', KEYS[1], ARGV[index], ARGV[index + 1])
    index = index + 2
end
return 1
