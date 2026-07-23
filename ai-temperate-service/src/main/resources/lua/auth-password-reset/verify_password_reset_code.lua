if redis.call('EXISTS', KEYS[4]) == 1 or redis.call('EXISTS', KEYS[6]) == 1 then return {8} end
local deviceHash = ARGV[1]
local challengeHash = ARGV[2]
local now = tonumber(ARGV[3])
local presentedDigest = ARGV[4]
local maxAttempts = tonumber(ARGV[5])
local maxTotalFailures = tonumber(ARGV[6])
local window = tonumber(ARGV[7])
local blockMillis = tonumber(ARGV[8])
local forgetTtl = tonumber(ARGV[9])

local flow = redis.call('HMGET', KEYS[1],
        'userId', 'identifier', 'deviceHash', 'challengeHash',
        'humanVerified', 'expiresAt', 'absoluteExpiresAt')
if not flow[1] then return {1} end
if flow[3] ~= deviceHash or flow[4] ~= challengeHash then return {3} end
if now >= tonumber(flow[6]) or now >= tonumber(flow[7]) then return {2} end
if flow[5] ~= '1' then return {4} end
local code = redis.call('HMGET', KEYS[2], 'digest', 'deliveryStatus', 'attempts')
if not code[1] or (code[2] ~= 'SUCCESS' and code[2] ~= 'UNKNOWN') then return {5} end

if code[1] ~= presentedDigest then
    if redis.call('EXISTS', KEYS[3]) == 0 then
        redis.call('HSET', KEYS[3], 'count', '0', 'createdAt', now)
        redis.call('PEXPIRE', KEYS[3], window)
    end
    local total = redis.call('HINCRBY', KEYS[3], 'count', 1)
    local attempts = redis.call('HINCRBY', KEYS[2], 'attempts', 1)
    if attempts >= maxAttempts then redis.call('UNLINK', KEYS[2]) end
    if total > maxTotalFailures then
        redis.call('PSETEX', KEYS[4], blockMillis, '1')
        redis.call('PSETEX', KEYS[6], blockMillis, '1')
        redis.call('UNLINK', KEYS[3])
        return {8}
    end
    if attempts >= maxAttempts then
        return {7}
    end
    return {6}
end

redis.call('HSET', KEYS[5],
        'schemaVersion', '2',
        'userId', flow[1],
        'deviceHash', deviceHash,
        'claimed', '0',
        'createdAt', now)
redis.call('PEXPIRE', KEYS[5], forgetTtl)
redis.call('UNLINK', KEYS[1], KEYS[2], KEYS[3])
return {0, flow[1]}
