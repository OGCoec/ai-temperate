-- OAuth complete 已创建 Refresh Session 后，原子轮换 PreAuth 并把会话标记为十五秒待裁决。
local function rejectAndRevokeCreatedSession()
    if redis.call('EXISTS', KEYS[4]) == 1 then
        local userId = redis.call('HGET', KEYS[4], 'userId')
        if userId ~= false and userId ~= nil and userId ~= '' then
            redis.call('HDEL', ARGV[15] .. userId, ARGV[7])
        end
        redis.call('UNLINK', KEYS[4])
    end
    return {0, 0}
end

-- 新 PreAuth 摘要碰撞时保留刚创建的 Session，让 Java 使用新的随机 Token 做有限次重试。
if redis.call('EXISTS', KEYS[2]) == 1 then
    return {-1, 0}
end
if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('EXISTS', KEYS[3]) == 0
        or redis.call('EXISTS', KEYS[4]) == 0
        or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'currentIpDigest') ~= ARGV[3]
        or redis.call('HGET', KEYS[1], 'webRtcGeneration') ~= ARGV[5]
        or redis.call('HGET', KEYS[1], 'webRtcPhase') ~= 'PENDING'
        or redis.call('HGET', KEYS[3], 'status') ~= 'RESUMED'
        or redis.call('HGET', KEYS[3], 'preAuthTokenDigest')
                ~= string.match(KEYS[1], '[^:]+$')
        or redis.call('HGET', KEYS[3], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[3], 'currentIpDigest') ~= ARGV[3]
        or redis.call('HGET', KEYS[3], 'oauthFlowDigest') ~= ARGV[4]
        or redis.call('HGET', KEYS[3], 'generation') ~= ARGV[5]
        or redis.call('HGET', KEYS[4], 'deviceHash') ~= ARGV[8] then
    return rejectAndRevokeCreatedSession()
end
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)
local verdictWindow = tonumber(ARGV[12])
local preAuthTtl = tonumber(ARGV[13])
if verdictWindow == nil or verdictWindow <= 0 or preAuthTtl == nil or preAuthTtl <= 0 then
    return rejectAndRevokeCreatedSession()
end
-- resume 已开始唯一的裁决窗口；complete 只能继承，禁止通过重试延长十五秒期限。
local deadline = tonumber(redis.call('HGET', KEYS[3], 'verdictDeadlineAt'))
if deadline == nil or deadline <= nowMillis or deadline > nowMillis + verdictWindow then
    return rejectAndRevokeCreatedSession()
end
local fields = redis.call('HGETALL', KEYS[1])
for index = 1, #fields, 2 do
    redis.call('HSET', KEYS[2], fields[index], fields[index + 1])
end
redis.call('HSET', KEYS[2],
        'authState', 'AUTHENTICATED',
        'sessionType', ARGV[10],
        'sessionRefDigest', ARGV[6],
        'lastDecisionContextDigest', ARGV[9],
        'lastSeenAt', ARGV[11],
        'webRtcPhase', 'PENDING',
        'webRtcGeneration', ARGV[5],
        'webRtcDeadlineAt', tostring(deadline),
        'webRtcOwner', 'OAUTH',
        'oauthWebRtcAttemptDigest', string.match(KEYS[3], '[^:]+$'))
redis.call('HDEL', KEYS[2], 'webRtcFailureReason', 'webRtcIps')
redis.call('PEXPIRE', KEYS[2], preAuthTtl)
redis.call('HSET', KEYS[3],
        'preAuthTokenDigest', ARGV[14],
        'refreshTokenDigest', ARGV[7],
        'status', 'RESUMED',
        'verdictDeadlineAt', tostring(deadline))
redis.call('HSET', KEYS[4],
        'riskVerdict', 'PENDING',
        'riskVerdictAttemptId', string.match(KEYS[3], '[^:]+$'),
        'riskVerdictGeneration', ARGV[5],
        'riskVerdictDeadlineAt', tostring(deadline))
redis.call('DEL', KEYS[1])
return {1, deadline}
