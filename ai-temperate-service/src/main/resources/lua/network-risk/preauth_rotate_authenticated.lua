-- 校验旧设备绑定后复制全部风控快照；只继承 VERIFIED，其他状态创建新的 REQUIRED generation。
-- ARGV 依次为 schema、设备、TTL、seenAt、sessionType、sessionRef、context、源 phase、源 generation、目标 phase、目标 generation、密文、start grace。
if redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('EXISTS', KEYS[2]) == 1 then
    return 0
end
local existing = redis.call('HGETALL', KEYS[1])
if #existing == 0 or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1] then
    return 0
end
local ttl = tonumber(ARGV[3])
local expectedSourceGeneration = tonumber(ARGV[9])
local generation = tonumber(ARGV[11])
local startGrace = tonumber(ARGV[13])
local sourcePhase = redis.call('HGET', KEYS[1], 'webRtcPhase')
local sourceGeneration = tonumber(redis.call('HGET', KEYS[1], 'webRtcGeneration'))
if ttl == nil or ttl <= 0
        or expectedSourceGeneration == nil or expectedSourceGeneration <= 0
        or generation == nil or generation <= 0
        or sourceGeneration == nil or sourceGeneration <= 0
        or (ARGV[10] ~= 'REQUIRED' and ARGV[10] ~= 'VERIFIED')
        or (ARGV[10] == 'VERIFIED' and ARGV[12] == '')
        or (ARGV[10] == 'REQUIRED' and (ARGV[12] ~= ''
            or startGrace == nil or startGrace <= 0)) then
    return 0
end

-- phase 和 generation 必须与 Java 解密证据时的源快照一致，避免并发 report 被旧轮换覆盖。
if sourcePhase ~= ARGV[8] or sourceGeneration ~= expectedSourceGeneration then
    return 0
end
if ARGV[10] == 'VERIFIED' then
    local sourceIps = redis.call('HGET', KEYS[1], 'webRtcIps')
    if sourcePhase ~= 'VERIFIED' or sourceGeneration ~= generation
            or sourceIps == false or sourceIps == '' then
        return 0
    end
elseif generation ~= sourceGeneration + 1 then
    return 0
end

redis.call('HSET', KEYS[2], unpack(existing))
redis.call(
        'HSET', KEYS[2],
        'schemaVersion', ARGV[1],
        'authState', 'AUTHENTICATED',
        'sessionType', ARGV[5],
        'sessionRefDigest', ARGV[6],
        'lastDecisionContextDigest', ARGV[7],
        'lastSeenAt', ARGV[4],
        'webRtcPhase', ARGV[10],
        'webRtcGeneration', ARGV[11],
        'activeChallengeNonce', '',
        'activeChallengeIpDigest', '',
        'activeChallengeContextDigest', '',
        'activeChallengeExpiresAt', '')
redis.call('HDEL', KEYS[2], 'webRtcDeadlineAt', 'webRtcFailureReason', 'webRtcIps')

-- 候选密文 AAD 绑定 Token 摘要，因此只有 VERIFIED 会由 Java 重加密后继承。
if ARGV[10] == 'VERIFIED' then
    redis.call('HSET', KEYS[2], 'webRtcIps', ARGV[12])
else
    local redisTime = redis.call('TIME')
    local nowMillis = tonumber(redisTime[1]) * 1000
            + math.floor(tonumber(redisTime[2]) / 1000)
    redis.call(
            'HSET', KEYS[2],
            'webRtcDeadlineAt', tostring(nowMillis + startGrace))
end
redis.call('PEXPIRE', KEYS[2], ARGV[3])
redis.call('UNLINK', KEYS[1])
return 1
