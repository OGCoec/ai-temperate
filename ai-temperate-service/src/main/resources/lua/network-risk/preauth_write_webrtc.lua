-- 只允许当前 PENDING generation 写入 report，并用 Redis TIME 判定服务端截止时间。
-- ARGV 依次为 schema、设备、作用域、HTTP IP、generation、目标阶段、失败原因、密文、是否有 IP 和 TTL。
if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'scope') ~= ARGV[3] then
    return 0
end
if redis.call('HGET', KEYS[1], 'currentIpDigest') ~= ARGV[4] then
    return -1
end
if redis.call('HGET', KEYS[1], 'webRtcGeneration') ~= ARGV[5] then
    return -2
end
if (ARGV[6] ~= 'VERIFIED' and ARGV[6] ~= 'FAILED')
        or (ARGV[9] ~= '0' and ARGV[9] ~= '1')
        or tonumber(ARGV[10]) == nil or tonumber(ARGV[10]) <= 0 then
    return 0
end
if (ARGV[6] == 'VERIFIED' and (ARGV[7] ~= '' or ARGV[8] == '' or ARGV[9] ~= '1'))
        or (ARGV[6] == 'FAILED' and ARGV[7] == '')
        or (ARGV[7] == 'IP_MISMATCH' and (ARGV[8] == '' or ARGV[9] ~= '1'))
        or (ARGV[7] ~= 'IP_MISMATCH' and ARGV[6] == 'FAILED'
            and (ARGV[8] ~= '' or ARGV[9] ~= '0')) then
    return 0
end

local currentPhase = redis.call('HGET', KEYS[1], 'webRtcPhase')
if currentPhase == 'VERIFIED' then
    redis.call('PEXPIRE', KEYS[1], ARGV[10])
    return 2
end
if currentPhase == 'FAILED' then
    redis.call('PEXPIRE', KEYS[1], ARGV[10])
    return 3
end
if currentPhase ~= 'PENDING' then
    return 0
end

local deadline = tonumber(redis.call('HGET', KEYS[1], 'webRtcDeadlineAt'))
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)
if deadline == nil or nowMillis > deadline then
    redis.call(
            'HSET', KEYS[1],
            'webRtcPhase', 'FAILED',
            'webRtcFailureReason', 'REPORT_TIMEOUT')
    redis.call('HDEL', KEYS[1], 'webRtcDeadlineAt', 'webRtcIps')
    redis.call('PEXPIRE', KEYS[1], ARGV[10])
    return 4
end

if ARGV[6] == 'VERIFIED' then
    redis.call(
            'HSET', KEYS[1],
            'webRtcPhase', 'VERIFIED',
            'webRtcIps', ARGV[8])
    redis.call('HDEL', KEYS[1], 'webRtcDeadlineAt', 'webRtcFailureReason')
else
    redis.call(
            'HSET', KEYS[1],
            'webRtcPhase', 'FAILED',
            'webRtcFailureReason', ARGV[7])
    redis.call('HDEL', KEYS[1], 'webRtcDeadlineAt')
    if ARGV[9] == '1' then
        redis.call('HSET', KEYS[1], 'webRtcIps', ARGV[8])
    else
        redis.call('HDEL', KEYS[1], 'webRtcIps')
    end
end
redis.call('PEXPIRE', KEYS[1], ARGV[10])
return 1
