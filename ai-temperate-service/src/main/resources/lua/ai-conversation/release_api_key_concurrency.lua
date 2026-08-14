local owner = ARGV[1]
local weight = tonumber(ARGV[2])
for index = 0, weight - 1 do
    local member = owner .. ':' .. index
    for keyIndex = 1, 3 do
        redis.call('ZREM', KEYS[keyIndex], member)
    end
end
return 1
