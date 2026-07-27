-- KEYS[1] 为作用域隔离后的 PreAuth Hash。
-- ARGV[1..4] 为设备摘要、作用域、新 IP 摘要和 TTL，后续参数为字段/值对。
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
if redis.call('HGET', KEYS[1], 'schemaVersion') ~= '4' then
    return 0
end
if redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[1] then
    return 0
end
if redis.call('HGET', KEYS[1], 'scope') ~= ARGV[2] then
    return 0
end
if ARGV[3] == '' or tonumber(ARGV[4]) == nil or tonumber(ARGV[4]) <= 0 then
    return 0
end
if ((#ARGV - 4) % 2) ~= 0 then
    return 0
end

-- WebRTC 结果只绑定生成它的 HTTP 出口；评估发现出口变化时必须在同一原子边界先清除旧结果。
local previousIpDigest = redis.call('HGET', KEYS[1], 'currentIpDigest')
if previousIpDigest ~= ARGV[3] then
    redis.call('HDEL', KEYS[1], 'webRtcStatus', 'webRtcIps')
end

if #ARGV > 4 then
    local values = {}
    for index = 5, #ARGV do
        values[#values + 1] = ARGV[index]
    end
    redis.call('HSET', KEYS[1], unpack(values))
end
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return 1
