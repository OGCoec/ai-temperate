-- 幂等恢复原 attempt；网络或 generation 变化时只允许一次替代，并从恢复时起启动固定裁决窗口。
if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('EXISTS', KEYS[2]) == 0
        or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[2], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[2], 'oauthFlowDigest') ~= ARGV[4]
        or redis.call('HGET', KEYS[2], 'preAuthTokenDigest')
                ~= string.match(KEYS[1], '[^:]+$') then
    return {0, 0, 0}
end
local currentGeneration = tonumber(redis.call('HGET', KEYS[1], 'webRtcGeneration'))
local requestedGeneration = tonumber(ARGV[5])
local window = tonumber(ARGV[6])
if currentGeneration == nil or requestedGeneration == nil or window == nil or window <= 0 then
    return {0, 0, 0}
end
local attemptStatus = redis.call('HGET', KEYS[2], 'status')
if attemptStatus == 'VERIFIED' then
    return {2, currentGeneration, tonumber(redis.call('HGET', KEYS[2], 'fallbackUsed')) or 0}
end
if attemptStatus == 'FAILED' or attemptStatus == 'EXPIRED' then
    return {0, currentGeneration, tonumber(redis.call('HGET', KEYS[2], 'fallbackUsed')) or 0}
end
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)
local suspendExpiresAt = tonumber(redis.call('HGET', KEYS[2], 'suspendExpiresAt'))
if suspendExpiresAt == nil or nowMillis >= suspendExpiresAt then
    redis.call('HSET', KEYS[2], 'status', 'EXPIRED')
    return {0, currentGeneration, tonumber(redis.call('HGET', KEYS[2], 'fallbackUsed')) or 0}
end
local fallbackUsed = tonumber(redis.call('HGET', KEYS[2], 'fallbackUsed')) or 0
local sameNetwork = redis.call('HGET', KEYS[2], 'currentIpDigest') == ARGV[3]
local sameGeneration = currentGeneration == requestedGeneration
if attemptStatus == 'RESUMED' and sameNetwork and sameGeneration then
    -- 重复 resume 只返回原状态，绝不能延长已经开始的异步裁决期限。
    return {1, currentGeneration, fallbackUsed}
end
local resultCode = 1
if not sameNetwork or not sameGeneration then
    if fallbackUsed ~= 0 then
        redis.call('HSET', KEYS[2], 'status', 'FAILED')
        return {0, currentGeneration, fallbackUsed}
    end
    fallbackUsed = 1
    resultCode = 3
    local replacementGeneration = currentGeneration
    if sameGeneration then
        if currentGeneration >= 9223372036854775806 then
            redis.call('HSET', KEYS[2], 'status', 'FAILED')
            return {0, currentGeneration, fallbackUsed}
        end
        replacementGeneration = currentGeneration + 1
    end
    currentGeneration = replacementGeneration
    redis.call('HSET', KEYS[1],
            'webRtcPhase', 'PENDING',
            'webRtcGeneration', tostring(currentGeneration))
    redis.call('HSET', KEYS[2],
            'currentIpDigest', ARGV[3],
            'generation', tostring(currentGeneration),
            'fallbackUsed', '1')
end
local verdictDeadline = nowMillis + window
redis.call('HSET', KEYS[1],
        'webRtcPhase', 'PENDING',
        'webRtcGeneration', tostring(currentGeneration),
        'webRtcDeadlineAt', tostring(verdictDeadline))
redis.call('HDEL', KEYS[1], 'webRtcFailureReason', 'webRtcIps')
redis.call('HSET', KEYS[2],
        'status', 'RESUMED',
        'verdictDeadlineAt', tostring(verdictDeadline))
-- 只延长键的存活时间，不改变 verdictDeadline；临近 OAuth Flow 过期的回调仍获得完整但唯一的窗口。
local preAuthTtl = redis.call('PTTL', KEYS[1])
local remaining = verdictDeadline - nowMillis
if preAuthTtl > 0 and preAuthTtl < remaining then
    redis.call('PEXPIRE', KEYS[1], remaining)
end
-- attempt 终态额外保留一分钟，只服务于 report 响应丢失后的只读查询；安全截止时间不随 TTL 延长。
redis.call('PEXPIREAT', KEYS[2], verdictDeadline + 60000)
return {resultCode, currentGeneration, fallbackUsed}
