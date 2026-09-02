-- H5 OAuth 只能从当前 VERIFIED generation 原子轮换，任何上下文变化都拒绝且不得创建 REQUIRED 下一代。
-- ARGV：schema、设备、当前 IP、旧决策上下文、TTL、seenAt、sessionType、sessionRef、新决策上下文、generation、新密文。
if redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('EXISTS', KEYS[2]) == 1 then
    return 0
end
local existing = redis.call('HGETALL', KEYS[1])
if #existing == 0
        or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1] then
    return 0
end

local ttl = tonumber(ARGV[5])
local expectedGeneration = tonumber(ARGV[10])
local sourceGeneration = tonumber(
        redis.call('HGET', KEYS[1], 'webRtcGeneration'))
local sourceIps = redis.call('HGET', KEYS[1], 'webRtcIps')
if ttl == nil or ttl <= 0
        or expectedGeneration == nil or expectedGeneration <= 0
        or sourceGeneration == nil
        or redis.call('HGET', KEYS[1], 'currentIpDigest') ~= ARGV[3]
        or redis.call('HGET', KEYS[1], 'lastDecisionContextDigest') ~= ARGV[4]
        or redis.call('HGET', KEYS[1], 'webRtcPhase') ~= 'VERIFIED'
        or sourceGeneration ~= expectedGeneration
        or sourceIps == false or sourceIps == ''
        or ARGV[11] == '' then
    return 0
end

redis.call('HSET', KEYS[2], unpack(existing))
redis.call(
        'HSET', KEYS[2],
        'schemaVersion', ARGV[1],
        'authState', 'AUTHENTICATED',
        'sessionType', ARGV[7],
        'sessionRefDigest', ARGV[8],
        'lastDecisionContextDigest', ARGV[9],
        'lastSeenAt', ARGV[6],
        'webRtcPhase', 'VERIFIED',
        'webRtcGeneration', ARGV[10],
        'webRtcIps', ARGV[11],
        'activeChallengeNonce', '',
        'activeChallengeIpDigest', '',
        'activeChallengeContextDigest', '',
        'activeChallengeExpiresAt', '')
redis.call(
        'HDEL', KEYS[2],
        'webRtcDeadlineAt',
        'webRtcFailureReason')
redis.call('PEXPIRE', KEYS[2], ARGV[5])
redis.call('UNLINK', KEYS[1])
return 1
