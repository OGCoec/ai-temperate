local now = tonumber(ARGV[1])
local expiresAt = tonumber(ARGV[2])
local globalLimit = tonumber(ARGV[3])
local userLimit = tonumber(ARGV[4])
local owner = ARGV[5]
local keyExpiresAt = tonumber(ARGV[6])
local weight = tonumber(ARGV[7])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)

if redis.call('ZCARD', KEYS[1]) + weight > globalLimit then
    return 2
end

if redis.call('ZCARD', KEYS[2]) + weight > userLimit then
    return 3
end

for index = 0, weight - 1 do
    local member = owner .. ':' .. index
    redis.call('ZADD', KEYS[1], expiresAt, member)
    redis.call('ZADD', KEYS[2], expiresAt, member)
end
redis.call('PEXPIREAT', KEYS[1], keyExpiresAt)
redis.call('PEXPIREAT', KEYS[2], keyExpiresAt)
return 1
