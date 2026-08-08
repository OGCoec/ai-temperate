-- KEYS[1] 为作用域隔离后的 PreAuth Hash。
-- ARGV 依次为 schema、设备、作用域、新 IP、start grace、TTL，后续参数为字段/值对。
if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'scope') ~= ARGV[3] then
    return 0
end
local startGrace = tonumber(ARGV[5])
local ttl = tonumber(ARGV[6])
if ARGV[4] == '' or startGrace == nil or startGrace <= 0
        or ttl == nil or ttl <= 0 or ((#ARGV - 6) % 2) ~= 0 then
    return 0
end

-- IP 变化的当前业务请求仍放行，但原子提升 generation 并创建新的 REQUIRED 任务。
local previousIpDigest = redis.call('HGET', KEYS[1], 'currentIpDigest')
if previousIpDigest ~= ARGV[4] then
    local redisTime = redis.call('TIME')
    local nowMillis = tonumber(redisTime[1]) * 1000
            + math.floor(tonumber(redisTime[2]) / 1000)
    local generation = tonumber(redis.call('HGET', KEYS[1], 'webRtcGeneration')) or 0
    redis.call(
            'HSET', KEYS[1],
            'webRtcPhase', 'REQUIRED',
            'webRtcGeneration', tostring(generation + 1),
            'webRtcDeadlineAt', tostring(nowMillis + startGrace))
    redis.call('HDEL', KEYS[1], 'webRtcFailureReason', 'webRtcIps')
end

if #ARGV > 6 then
    local values = {}
    for index = 7, #ARGV do
        values[#values + 1] = ARGV[index]
    end
    redis.call('HSET', KEYS[1], unpack(values))
end
redis.call('PEXPIRE', KEYS[1], ARGV[6])
return 1
