if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end

local pairCount = (#ARGV - 2) / 2
if redis.call('HLEN', KEYS[1]) + pairCount > tonumber(ARGV[2]) then
    return -1
end

for index = 3, #ARGV, 2 do
    redis.call('HSET', KEYS[1], ARGV[index], ARGV[index + 1])
end
return 1
