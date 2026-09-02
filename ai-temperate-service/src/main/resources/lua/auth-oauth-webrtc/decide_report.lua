-- 对 OAuth report 同时裁决 PreAuth、attempt 和 Refresh Session；失败分支立即撤销会话及用户索引字段。
if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('EXISTS', KEYS[2]) == 0
        or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'currentIpDigest') ~= ARGV[3]
        or redis.call('HGET', KEYS[1], 'webRtcGeneration') ~= ARGV[4]
        or redis.call('HGET', KEYS[2], 'generation') ~= ARGV[4]
        or redis.call('HGET', KEYS[2], 'preAuthTokenDigest')
                ~= string.match(KEYS[1], '[^:]+$')
        or redis.call('HGET', KEYS[2], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[2], 'currentIpDigest') ~= ARGV[3] then
    return 0
end
local phase = redis.call('HGET', KEYS[1], 'webRtcPhase')
local attemptStatus = redis.call('HGET', KEYS[2], 'status')
if phase == 'FAILED' or attemptStatus == 'FAILED' or attemptStatus == 'EXPIRED' then return 3 end
local attemptDigest = string.match(KEYS[2], '[^:]+$')
local refreshDigest = redis.call('HGET', KEYS[2], 'refreshTokenDigest')
if refreshDigest == false or refreshDigest == nil or refreshDigest == '' then return 0 end
local refreshKey = ARGV[10] .. refreshDigest
if redis.call('EXISTS', refreshKey) == 0 then return 0 end
-- 已完成的重复 report 只有在同一 Session 仍为 ACTIVE 时才幂等成功。
if phase == 'VERIFIED' and attemptStatus == 'VERIFIED' then
    if redis.call('HGET', refreshKey, 'riskVerdict') == 'ACTIVE' then return 2 end
    return 0
end
local convergingVerified = phase == 'VERIFIED' and attemptStatus == 'RESUMED'
if (phase ~= 'PENDING' and not convergingVerified) or attemptStatus ~= 'RESUMED' then
    return 0
end
-- OAuth owner 与用途隔离 HMAC 必须匹配，历史 VERIFIED 只能由原 attempt 收敛会话。
if redis.call('HGET', KEYS[1], 'webRtcOwner') ~= 'OAUTH'
        or redis.call('HGET', KEYS[1], 'oauthWebRtcAttemptDigest') ~= attemptDigest
        or redis.call('HGET', refreshKey, 'riskVerdict') ~= 'PENDING'
        or redis.call('HGET', refreshKey, 'riskVerdictAttemptId') ~= attemptDigest
        or redis.call('HGET', refreshKey, 'riskVerdictGeneration') ~= ARGV[4] then
    return 0
end
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)
local deadline = tonumber(redis.call('HGET', KEYS[2], 'verdictDeadlineAt'))
local target = ARGV[5]
local failureReason = ARGV[6]
local deadlineExpired = deadline == nil or nowMillis >= deadline
if deadlineExpired then
    target = 'FAILED'
    failureReason = 'REPORT_TIMEOUT'
end
if target == 'VERIFIED' then
    if failureReason ~= '' or ARGV[7] == '' or ARGV[8] ~= '1' then return 0 end
    if phase ~= 'VERIFIED' then
        redis.call('HSET', KEYS[1], 'webRtcPhase', 'VERIFIED', 'webRtcIps', ARGV[7])
    end
    redis.call('HDEL', KEYS[1], 'webRtcDeadlineAt', 'webRtcFailureReason')
    redis.call('HSET', KEYS[2], 'status', 'VERIFIED')
    redis.call('HSET', refreshKey, 'riskVerdict', 'ACTIVE')
    redis.call('HDEL', refreshKey,
            'riskVerdictAttemptId', 'riskVerdictGeneration', 'riskVerdictDeadlineAt')
else
    if failureReason == '' then return 0 end
    redis.call('HSET', KEYS[1], 'webRtcPhase', 'FAILED', 'webRtcFailureReason', failureReason)
    redis.call('HDEL', KEYS[1], 'webRtcDeadlineAt', 'webRtcIps')
    redis.call('HSET', KEYS[2], 'status', deadlineExpired and 'EXPIRED' or 'FAILED')
    local userId = redis.call('HGET', refreshKey, 'userId')
    if userId ~= false and userId ~= nil and userId ~= '' then
        redis.call('HDEL', ARGV[11] .. userId, refreshDigest)
    end
    redis.call('UNLINK', refreshKey)
end
redis.call('HDEL', KEYS[1], 'webRtcOwner', 'oauthWebRtcAttemptDigest')
redis.call('PEXPIRE', KEYS[1], ARGV[9])
local attemptRetention = tonumber(ARGV[9]) or 60000
redis.call('PEXPIRE', KEYS[2], math.min(60000, attemptRetention))
return deadlineExpired and 4 or 1
