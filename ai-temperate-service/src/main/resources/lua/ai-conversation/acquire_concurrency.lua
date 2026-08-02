local now = tonumber(ARGV[1])
local expiresAt = tonumber(ARGV[2])
local globalLimit = tonumber(ARGV[3])
local userLimit = tonumber(ARGV[4])
local owner = ARGV[5]
local keyExpiresAt = tonumber(ARGV[6])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)

if redis.call('ZCARD', KEYS[1]) >= globalLimit then
    return 2
end

if redis.call('ZCARD', KEYS[2]) >= userLimit then
    return 3
end

redis.call('ZADD', KEYS[1], expiresAt, owner)
redis.call('ZADD', KEYS[2], expiresAt, owner)
redis.call('PEXPIREAT', KEYS[1], keyExpiresAt)
redis.call('PEXPIREAT', KEYS[2], keyExpiresAt)
return 1
