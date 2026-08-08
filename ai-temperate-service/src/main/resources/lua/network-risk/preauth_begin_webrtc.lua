-- 原子把 REQUIRED 转为 PENDING；重复 start 只读取原截止时间，绝不续期。
-- ARGV 依次为 schema、设备、作用域、HTTP IP、generation、校验窗口和 TTL。
if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'scope') ~= ARGV[3] then
    return {0, 0, 0, 0}
end
local generation = tonumber(redis.call('HGET', KEYS[1], 'webRtcGeneration'))
local expectedGeneration = tonumber(ARGV[5])
local verificationWindow = tonumber(ARGV[6])
local ttl = tonumber(ARGV[7])
if generation == nil or expectedGeneration == nil or expectedGeneration <= 0
        or verificationWindow == nil or verificationWindow <= 0
        or ttl == nil or ttl <= 0 then
    return {0, 0, 0, 0}
end
if redis.call('HGET', KEYS[1], 'currentIpDigest') ~= ARGV[4] then
    return {-1, generation, 0, 0}
end
if generation ~= expectedGeneration then
    return {-2, generation, 0, 0}
end

local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)
local currentPhase = redis.call('HGET', KEYS[1], 'webRtcPhase')
local deadline = tonumber(redis.call('HGET', KEYS[1], 'webRtcDeadlineAt'))

if currentPhase == 'VERIFIED' then
    redis.call('PEXPIRE', KEYS[1], ARGV[7])
    return {2, generation, 0, 0}
end
if currentPhase == 'FAILED' then
    redis.call('PEXPIRE', KEYS[1], ARGV[7])
    return {3, generation, 0, 0}
end
if currentPhase == 'PENDING' then
    if deadline == nil then
        return {0, generation, 0, 0}
    end
    if nowMillis > deadline then
        redis.call(
                'HSET', KEYS[1],
                'webRtcPhase', 'FAILED',
                'webRtcFailureReason', 'REPORT_TIMEOUT')
        redis.call('HDEL', KEYS[1], 'webRtcDeadlineAt', 'webRtcIps')
        redis.call('PEXPIRE', KEYS[1], ARGV[7])
        return {6, generation, 0, 0}
    end
    redis.call('PEXPIRE', KEYS[1], ARGV[7])
    return {5, generation, deadline, deadline - nowMillis}
end
if currentPhase ~= 'REQUIRED' or deadline == nil then
    return {0, generation, 0, 0}
end
if nowMillis > deadline then
    redis.call(
            'HSET', KEYS[1],
            'webRtcPhase', 'FAILED',
            'webRtcFailureReason', 'START_TIMEOUT')
    redis.call('HDEL', KEYS[1], 'webRtcDeadlineAt', 'webRtcIps')
    redis.call('PEXPIRE', KEYS[1], ARGV[7])
    return {4, generation, 0, 0}
end

local reportDeadline = nowMillis + verificationWindow
redis.call(
        'HSET', KEYS[1],
        'webRtcPhase', 'PENDING',
        'webRtcDeadlineAt', tostring(reportDeadline))
redis.call('HDEL', KEYS[1], 'webRtcFailureReason', 'webRtcIps')
redis.call('PEXPIRE', KEYS[1], ARGV[7])
return {1, generation, reportDeadline, verificationWindow}
