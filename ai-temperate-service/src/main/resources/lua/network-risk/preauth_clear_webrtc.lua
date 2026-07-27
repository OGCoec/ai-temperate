-- KEYS[1] 为作用域隔离后的 PreAuth Hash。
-- ARGV 依次为设备摘要、作用域、当前 IP 摘要和 TTL。
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
if redis.call('HGET', KEYS[1], 'currentIpDigest') ~= ARGV[3] then
    return 0
end
if tonumber(ARGV[4]) == nil or tonumber(ARGV[4]) <= 0 then
    return 0
end

redis.call('HDEL', KEYS[1], 'webRtcStatus', 'webRtcIps')
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return 1
