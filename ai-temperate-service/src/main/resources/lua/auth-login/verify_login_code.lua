local deviceHash = ARGV[1]
local challengeHash = ARGV[2]
local now = tonumber(ARGV[3])
local presentedDigest = ARGV[4]
local maxAttempts = tonumber(ARGV[5])
local idleTtl = tonumber(ARGV[6])

local flow = redis.call('HMGET', KEYS[1],
        'strategyType', 'identifier', 'userId', 'deviceHash', 'challengeHash',
        'humanVerified', 'createdAt', 'expiresAt', 'absoluteExpiresAt')
if not flow[1] then return {1} end
if flow[4] ~= deviceHash or flow[5] ~= challengeHash then return {3} end
if now >= tonumber(flow[8]) or now >= tonumber(flow[9]) then return {2} end
if flow[6] ~= '1' then return {4} end
local code = redis.call('HMGET', KEYS[2], 'digest', 'deliveryStatus', 'attempts')
if not code[1] or (code[2] ~= 'SUCCESS' and code[2] ~= 'UNKNOWN') then return {5} end
if code[1] ~= presentedDigest then
    local attempts = redis.call('HINCRBY', KEYS[2], 'attempts', 1)
    if attempts >= maxAttempts then redis.call('UNLINK', KEYS[2]); return {7} end
    return {6}
end
redis.call('UNLINK', KEYS[2])
redis.call('HSET', KEYS[1], 'codeVerified', '1')
local renewedExpiresAt = math.min(now + idleTtl, tonumber(flow[9]))
redis.call('HSET', KEYS[1], 'expiresAt', renewedExpiresAt)
redis.call('PEXPIRE', KEYS[1], renewedExpiresAt - now)
return {0, flow[1], flow[2], flow[3], flow[6], flow[7],
        tostring(renewedExpiresAt), flow[9]}
