local IDLE_TTL_MILLIS = 600000
local ABSOLUTE_TTL_MILLIS = 1800000

local createdAt = tonumber(ARGV[8])
local expiresAt = tonumber(ARGV[9])
local absoluteExpiresAt = tonumber(ARGV[10])
local idleTtlMillis = tonumber(ARGV[15])
if createdAt == nil or expiresAt == nil or absoluteExpiresAt == nil
        or expiresAt - createdAt ~= IDLE_TTL_MILLIS
        or absoluteExpiresAt - createdAt ~= ABSOLUTE_TTL_MILLIS
        or idleTtlMillis ~= IDLE_TTL_MILLIS then
    return -1
end

if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then
    return 1
end

redis.call('HSET', KEYS[1],
        'schemaVersion', ARGV[1], 'email', ARGV[2], 'phone', ARGV[3],
        'deviceHash', ARGV[4], 'ipHash', ARGV[5], 'flowCsrfHash', ARGV[6],
        'challengeHash', ARGV[7], 'createdAt', ARGV[8], 'expiresAt', ARGV[9],
        'absoluteExpiresAt', ARGV[10], 'humanVerified', ARGV[11],
        'emailVerified', ARGV[12], 'phoneVerified', ARGV[13])
redis.call('PEXPIRE', KEYS[1], IDLE_TTL_MILLIS)
redis.call('SET', KEYS[2], ARGV[14], 'PX', IDLE_TTL_MILLIS)
return 0
