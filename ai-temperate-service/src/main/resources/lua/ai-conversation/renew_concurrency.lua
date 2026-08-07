local owner = ARGV[1]
local weight = tonumber(ARGV[2])
local expiresAt = ARGV[3]
local keyExpiresAt = ARGV[4]

for index = 0, weight - 1 do
    local member = owner .. ':' .. index
    if redis.call('ZSCORE', KEYS[1], member) == false
            or redis.call('ZSCORE', KEYS[2], member) == false then
        return 0
    end
end

for index = 0, weight - 1 do
    local member = owner .. ':' .. index
    redis.call('ZADD', KEYS[1], 'XX', expiresAt, member)
    redis.call('ZADD', KEYS[2], 'XX', expiresAt, member)
end
redis.call('PEXPIREAT', KEYS[1], keyExpiresAt)
redis.call('PEXPIREAT', KEYS[2], keyExpiresAt)
return 1
