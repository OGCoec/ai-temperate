-- KEYS[1] 为作用域隔离后的 PreAuth Hash。
-- ARGV 依次为设备摘要、作用域、当前 IP 摘要、状态、密文、是否有 IP 和 TTL。
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
    return -1
end
if (ARGV[4] ~= 'true' and ARGV[4] ~= 'false') or ARGV[5] == '' then
    return 0
end
if (ARGV[6] ~= '0' and ARGV[6] ~= '1')
        or tonumber(ARGV[7]) == nil
        or tonumber(ARGV[7]) <= 0 then
    return 0
end

local previousStatus = redis.call('HGET', KEYS[1], 'webRtcStatus')
local previousIps = redis.call('HGET', KEYS[1], 'webRtcIps')

-- 同一 IP 已验证成功后，迟到的空结果或不匹配结果不能把可信状态降级。
if previousStatus == 'true' and ARGV[4] == 'false' then
    redis.call('PEXPIRE', KEYS[1], ARGV[7])
    return 2
end

-- 已有失败详情不能被迟到的空报告覆盖；新的非空报告和成功报告仍可替换旧失败。
if previousStatus == 'false'
        and previousIps ~= false
        and previousIps ~= ''
        and ARGV[4] == 'false'
        and ARGV[6] == '0' then
    redis.call('PEXPIRE', KEYS[1], ARGV[7])
    return 3
end

redis.call(
        'HSET',
        KEYS[1],
        'webRtcStatus', ARGV[4],
        'webRtcIps', ARGV[5])
redis.call('PEXPIRE', KEYS[1], ARGV[7])
return 1
