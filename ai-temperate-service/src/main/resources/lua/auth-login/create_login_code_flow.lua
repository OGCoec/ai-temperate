local schemaVersion = ARGV[1]
local strategyType = ARGV[2]
local identifier = ARGV[3]
local userId = ARGV[4]
local deviceHash = ARGV[5]
local challengeHash = ARGV[6]
local createdAt = tonumber(ARGV[7])
local idleTtl = tonumber(ARGV[8])
local absoluteTtl = tonumber(ARGV[9])

if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then
    return 1
end
local expiresAt = createdAt + idleTtl
local absoluteExpiresAt = createdAt + absoluteTtl
redis.call('HSET', KEYS[1],
        'schemaVersion', schemaVersion,
        'strategyType', strategyType,
        'identifier', identifier,
        'userId', userId,
        'deviceHash', deviceHash,
        'challengeHash', challengeHash,
        'humanVerified', '0',
        'codeVerified', '0',
        'sendCount', '0',
        'createdAt', createdAt,
        'expiresAt', expiresAt,
        'absoluteExpiresAt', absoluteExpiresAt)
redis.call('PEXPIRE', KEYS[1], idleTtl)
redis.call('PSETEX', KEYS[2], idleTtl, challengeHash)
return 0
