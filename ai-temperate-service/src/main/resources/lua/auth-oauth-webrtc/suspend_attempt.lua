-- 将已有 PENDING generation 绑定到 OAuth Flow；只延长悬挂期，不创建新 PreAuth 或 generation。
if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'currentIpDigest') ~= ARGV[3] then
    return {0, 0}
end
local generation = tonumber(redis.call('HGET', KEYS[1], 'webRtcGeneration'))
if generation == nil or generation ~= tonumber(ARGV[5]) then
    return {0, generation or 0}
end
local phase = redis.call('HGET', KEYS[1], 'webRtcPhase')
if phase == 'VERIFIED' then
    return {2, generation}
end
if phase ~= 'PENDING' then
    return {0, generation}
end
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)
local suspendExpiresAt = tonumber(ARGV[7])
if suspendExpiresAt == nil or suspendExpiresAt <= nowMillis then
    return {0, generation}
end
if redis.call('EXISTS', KEYS[2]) == 1 then
    return {0, generation}
end
redis.call('HSET', KEYS[2],
        'schemaVersion', '1',
        'status', 'OAUTH_SUSPENDED',
        'preAuthTokenDigest', string.match(KEYS[1], '[^:]+$'),
        'deviceDigest', ARGV[2],
        'currentIpDigest', ARGV[3],
        'oauthFlowDigest', ARGV[4],
        'generation', ARGV[5],
        'probeRunDigest', ARGV[6],
        'fallbackUsed', '0',
        'suspendExpiresAt', ARGV[7])
redis.call('PEXPIREAT', KEYS[2], suspendExpiresAt)
redis.call('HSET', KEYS[1], 'webRtcDeadlineAt', ARGV[7])
local preAuthTtl = redis.call('PTTL', KEYS[1])
local remaining = suspendExpiresAt - nowMillis
if preAuthTtl > 0 and preAuthTtl < remaining then
    redis.call('PEXPIRE', KEYS[1], remaining)
end
return {1, generation}
