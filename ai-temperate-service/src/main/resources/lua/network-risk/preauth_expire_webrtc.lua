-- 原子把越过截止点的 REQUIRED/PENDING 转为对应超时终态；等于截止点时仍允许 start/report。
-- ARGV 依次为 schema、设备、作用域、HTTP IP、generation 和 TTL。
if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'scope') ~= ARGV[3]
        or redis.call('HGET', KEYS[1], 'currentIpDigest') ~= ARGV[4]
        or redis.call('HGET', KEYS[1], 'webRtcGeneration') ~= ARGV[5] then
    return 0
end
local ttl = tonumber(ARGV[6])
local deadline = tonumber(redis.call('HGET', KEYS[1], 'webRtcDeadlineAt'))
local currentPhase = redis.call('HGET', KEYS[1], 'webRtcPhase')
if ttl == nil or ttl <= 0 or deadline == nil
        or (currentPhase ~= 'REQUIRED' and currentPhase ~= 'PENDING') then
    return 0
end
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)
if nowMillis <= deadline then
    return 0
end
local failureReason = currentPhase == 'REQUIRED'
        and 'START_TIMEOUT' or 'REPORT_TIMEOUT'
redis.call(
        'HSET', KEYS[1],
        'webRtcPhase', 'FAILED',
        'webRtcFailureReason', failureReason)
redis.call('HDEL', KEYS[1], 'webRtcDeadlineAt', 'webRtcIps')
redis.call('PEXPIRE', KEYS[1], ARGV[6])
return 1
