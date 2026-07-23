local userId = ARGV[1]
local publicId = ARGV[2]
local tokenHash = ARGV[3]
local deviceHash = ARGV[4]
local csrfHash = ARGV[5]
local email = ARGV[6]
local phone = ARGV[7]
local maxSessions = tonumber(ARGV[8])
local refreshTtlMillis = tonumber(ARGV[9])

if redis.call('EXISTS', KEYS[1]) == 1
        or redis.call('HEXISTS', KEYS[2], tokenHash) == 1 then
    return {1}
end

if redis.call('HLEN', KEYS[2]) >= maxSessions then
    return {2}
end

local serverTime = redis.call('TIME')
local nowMillis = (tonumber(serverTime[1]) * 1000)
        + math.floor(tonumber(serverTime[2]) / 1000)
local expiresAt = nowMillis + refreshTtlMillis

redis.call('HSET', KEYS[1],
        'userId', userId,
        'publicId', publicId,
        'csrfHash', csrfHash,
        'email', email,
        'phone', phone,
        'deviceHash', deviceHash)
redis.call('PEXPIREAT', KEYS[1], expiresAt)

-- 用户索引的值保存完整 RT Key，撤销阶段才能直接批量 UNLINK 而无需逐项拼接键名。
redis.call('HSET', KEYS[2], tokenHash, KEYS[1])
redis.call('HPEXPIREAT', KEYS[2], expiresAt, 'FIELDS', 1, tokenHash)
redis.call('PEXPIREAT', KEYS[2], expiresAt)

return {0, userId, publicId, csrfHash, email, phone, deviceHash, tostring(expiresAt)}
