local now = tonumber(ARGV[1])
local expiresAt = tonumber(ARGV[2])
local keyLimit = tonumber(ARGV[3])
local accountLimit = tonumber(ARGV[4])
local globalLimit = tonumber(ARGV[5])
local owner = ARGV[6]
local keyExpiresAt = tonumber(ARGV[7])
local weight = tonumber(ARGV[8])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)

if redis.call('ZCARD', KEYS[1]) + weight > keyLimit then
    return 2
end
if redis.call('ZCARD', KEYS[2]) + weight > accountLimit then
    return 3
end
if redis.call('ZCARD', KEYS[3]) + weight > globalLimit then
    return 4
end

for index = 0, weight - 1 do
    local member = owner .. ':' .. index
    redis.call('ZADD', KEYS[1], expiresAt, member)
    redis.call('ZADD', KEYS[2], expiresAt, member)
    redis.call('ZADD', KEYS[3], expiresAt, member)
end
redis.call('PEXPIREAT', KEYS[1], keyExpiresAt)
redis.call('PEXPIREAT', KEYS[2], keyExpiresAt)
redis.call('PEXPIREAT', KEYS[3], keyExpiresAt)
return 1
