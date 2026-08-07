local owner = ARGV[1]
local weight = tonumber(ARGV[2])
for index = 0, weight - 1 do
    local member = owner .. ':' .. index
    redis.call('ZREM', KEYS[1], member)
    redis.call('ZREM', KEYS[2], member)
end
return 1
