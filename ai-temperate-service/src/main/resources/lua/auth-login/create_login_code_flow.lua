local schemaVersion = ARGV[1]
local strategyType = ARGV[2]
local purpose = ARGV[3]
local identifier = ARGV[4]
local userId = ARGV[5]
local deviceHash = ARGV[6]
local challengeHash = ARGV[7]
local createdAt = tonumber(ARGV[8])
local idleTtl = tonumber(ARGV[9])
local absoluteTtl = tonumber(ARGV[10])

if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then
    return 1
end
local expiresAt = createdAt + idleTtl
local absoluteExpiresAt = createdAt + absoluteTtl
redis.call('HSET', KEYS[1],
        'schemaVersion', schemaVersion,
        'strategyType', strategyType,
        'purpose', purpose,
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
