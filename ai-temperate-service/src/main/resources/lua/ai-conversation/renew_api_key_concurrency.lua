local owner = ARGV[1]
local weight = tonumber(ARGV[2])
local expiresAt = ARGV[3]
local keyExpiresAt = ARGV[4]

for index = 0, weight - 1 do
    local member = owner .. ':' .. index
    for keyIndex = 1, 3 do
        if redis.call('ZSCORE', KEYS[keyIndex], member) == false then
            return 0
        end
    end
end
for index = 0, weight - 1 do
    local member = owner .. ':' .. index
    for keyIndex = 1, 3 do
        redis.call('ZADD', KEYS[keyIndex], 'XX', expiresAt, member)
        redis.call('PEXPIREAT', KEYS[keyIndex], keyExpiresAt)
    end
end
return 1
