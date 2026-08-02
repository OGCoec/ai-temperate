if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end

local maximumFields = tonumber(ARGV[3])
local writeCount = tonumber(ARGV[4])
local deleteIndex = 5 + writeCount * 2
local removableCount = 0
for index = deleteIndex, #ARGV do
    if redis.call('HEXISTS', KEYS[1], ARGV[index]) == 1 then
        removableCount = removableCount + 1
    end
end

if redis.call('HLEN', KEYS[1]) - removableCount + writeCount > maximumFields then
    return -1
end

redis.call('HSET', KEYS[1], 'meta', ARGV[2])
local index = 5
for ignored = 1, writeCount do
    redis.call('HSET', KEYS[1], ARGV[index], ARGV[index + 1])
    index = index + 2
end
while index <= #ARGV do
    redis.call('HDEL', KEYS[1], ARGV[index])
    index = index + 1
end
return 1
