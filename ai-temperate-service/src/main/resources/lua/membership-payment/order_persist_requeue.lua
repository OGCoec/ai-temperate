local dirty_key = KEYS[1]
local processing_key = KEYS[2]
local count = tonumber(ARGV[1])
local ready_at = ARGV[2]
if count == 0 then
    return 0
end

local members = {}
local expected = {}
local argument_index = 3
for index = 1, count do
    members[index] = ARGV[argument_index]
    expected[index] = tonumber(ARGV[argument_index + 1])
    argument_index = argument_index + 2
end

local scores = redis.call('ZMSCORE', processing_key, unpack(members))
local zrem = {'ZREM', processing_key}
local zadd = {'ZADD', dirty_key}
local requeued = 0
for index = 1, count do
    if scores[index] and tonumber(scores[index]) == expected[index] then
        zrem[#zrem + 1] = members[index]
        zadd[#zadd + 1] = ready_at
        zadd[#zadd + 1] = members[index]
        requeued = requeued + 1
    end
end
if requeued > 0 then
    redis.call(unpack(zrem))
    redis.call(unpack(zadd))
end
return requeued
