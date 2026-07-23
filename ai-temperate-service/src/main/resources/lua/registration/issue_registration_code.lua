local COOLDOWN_MILLIS = 60000
local ALLOWED_SENDS = 5
local CODE_MAX_TTL_MILLIS = 300000
local WINDOW_MILLIS = 300000
local BLOCK_SECONDS = 7200

local codeMaxTtlMillis = tonumber(ARGV[8])
local cooldownMillis = tonumber(ARGV[9])
local sendLimit = tonumber(ARGV[10])
local windowMillis = tonumber(ARGV[14])
local blockSeconds = tonumber(ARGV[15])
if codeMaxTtlMillis ~= CODE_MAX_TTL_MILLIS
        or cooldownMillis ~= COOLDOWN_MILLIS
        or sendLimit ~= ALLOWED_SENDS
        or windowMillis ~= WINDOW_MILLIS
        or blockSeconds ~= BLOCK_SECONDS then
    return redis.error_reply('invalid verification send boundaries')
end

if redis.call('EXISTS', KEYS[4]) == 1 then
    return 8
end
if redis.call('EXISTS', KEYS[5]) == 1 then
    return 8
end
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 1
end
local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
local absoluteExpiresAt = tonumber(redis.call('HGET', KEYS[1], 'absoluteExpiresAt'))
local now = tonumber(ARGV[5])
if expiresAt == nil or absoluteExpiresAt == nil or now == nil
        or now >= expiresAt or now >= absoluteExpiresAt then
    return 2
end

local access = redis.call('HMGET', KEYS[1],
        'flowCsrfHash', 'deviceHash', 'ipHash', 'challengeHash')
if access[1] ~= ARGV[1] or access[2] ~= ARGV[2]
        or access[3] ~= ARGV[3] or access[4] ~= ARGV[4] then
    return 3
end
if redis.call('HGET', KEYS[1], 'humanVerified') ~= '1' then
    return 4
end

if redis.call('EXISTS', KEYS[3]) == 0 then
    redis.call('HSET', KEYS[3], 'createdAt', ARGV[5])
    redis.call('PEXPIRE', KEYS[3], windowMillis)
end
local lastIssuedAt = tonumber(redis.call('HGET', KEYS[3], ARGV[12]) or '0')
if lastIssuedAt > 0 and now - lastIssuedAt < cooldownMillis then
    local violations = redis.call('HINCRBY', KEYS[3], ARGV[13], 1)
    if violations > ALLOWED_SENDS then
        redis.call('SET', KEYS[4], '1', 'EX', blockSeconds)
        redis.call('SET', KEYS[5], '1', 'EX', blockSeconds)
        redis.call('UNLINK', KEYS[3])
        return 8
    end
    return 5
end

local flowPttl = redis.call('PTTL', KEYS[1])
if flowPttl <= 0 then
    return 2
end
local codeTtl = math.min(codeMaxTtlMillis, flowPttl)
redis.call('HSET', KEYS[2], 'digest', ARGV[6], 'sendOperationId', ARGV[7],
        'deliveryStatus', 'PENDING', 'attempts', '0', 'issuedAt', ARGV[5])
redis.call('PEXPIRE', KEYS[2], codeTtl)
redis.call('HSET', KEYS[3], ARGV[12], ARGV[5])
return 0
