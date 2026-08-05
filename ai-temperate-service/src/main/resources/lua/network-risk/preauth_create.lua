-- 原子创建随机 PreAuth Hash，并用 Redis TIME 开启 REQUIRED 的 start grace。
-- ARGV 依次为 TTL、start grace、期望 schema，后续参数为普通字段/值对。
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end
local ttl = tonumber(ARGV[1])
local startGrace = tonumber(ARGV[2])
if ttl == nil or ttl <= 0 or startGrace == nil or startGrace <= 0
        or ARGV[3] == '' or ((#ARGV - 3) % 2) ~= 0 then
    return 0
end
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)
if #ARGV > 3 then
    local values = {}
    for index = 4, #ARGV do
        values[#values + 1] = ARGV[index]
    end
    redis.call('HSET', KEYS[1], unpack(values))
end
redis.call(
        'HSET',
        KEYS[1],
        'schemaVersion', ARGV[3],
        'webRtcPhase', 'REQUIRED',
        'webRtcGeneration', '1',
        'webRtcDeadlineAt', tostring(nowMillis + startGrace))
redis.call('HDEL', KEYS[1], 'webRtcFailureReason', 'webRtcIps')
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return 1
