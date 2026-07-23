local CODE_ATTEMPT_LIMIT = 5
local TOTAL_FAILURE_LIMIT = 10
local WINDOW_MILLIS = 300000
local BLOCK_SECONDS = 7200

local function snapshot()
    local values = redis.call('HMGET', KEYS[1], 'email', 'phone', 'humanVerified',
            'emailVerified', 'phoneVerified', 'createdAt', 'expiresAt', 'absoluteExpiresAt')
    local completing = '0'
    if redis.call('HEXISTS', KEYS[1], 'completionClaim') == 1 then
        completing = '1'
    end
    return {0, values[1], values[2], values[3], values[4], values[5],
            completing, values[6], values[7], values[8]}
end

if redis.call('EXISTS', KEYS[5]) == 1 then
    return {8}
end
if redis.call('EXISTS', KEYS[6]) == 1 then
    return {8}
end
if redis.call('EXISTS', KEYS[1]) == 0 then
    return {1}
end
local now = tonumber(ARGV[5])
local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt'))
local absoluteExpiresAt = tonumber(redis.call('HGET', KEYS[1], 'absoluteExpiresAt'))
if now == nil or expiresAt == nil or absoluteExpiresAt == nil
        or now >= expiresAt or now >= absoluteExpiresAt then
    return {2}
end
local access = redis.call('HMGET', KEYS[1],
        'flowCsrfHash', 'deviceHash', 'ipHash', 'challengeHash')
if access[1] ~= ARGV[1] or access[2] ~= ARGV[2]
        or access[3] ~= ARGV[3] or access[4] ~= ARGV[4] then
    return {3}
end
if redis.call('HGET', KEYS[1], 'humanVerified') ~= '1' then
    return {4}
end
if tonumber(ARGV[8]) ~= CODE_ATTEMPT_LIMIT
        or tonumber(ARGV[9]) ~= TOTAL_FAILURE_LIMIT
        or tonumber(ARGV[10]) ~= WINDOW_MILLIS
        or tonumber(ARGV[11]) ~= BLOCK_SECONDS then
    return redis.error_reply('invalid combined verification boundaries')
end

local email = redis.call('HMGET', KEYS[2], 'digest', 'deliveryStatus')
local phone = redis.call('HMGET', KEYS[3], 'digest', 'deliveryStatus')
local emailMatches = email[1] ~= false
        and (email[2] == 'SUCCESS' or email[2] == 'UNKNOWN')
        and email[1] == ARGV[6]
local phoneMatches = phone[1] ~= false
        and (phone[2] == 'SUCCESS' or phone[2] == 'UNKNOWN')
        and phone[1] == ARGV[7]
if emailMatches and phoneMatches then
    redis.call('UNLINK', KEYS[2], KEYS[3], KEYS[4])
    redis.call('HSET', KEYS[1], 'emailVerified', '1', 'phoneVerified', '1')
    return snapshot()
end

if redis.call('EXISTS', KEYS[4]) == 0 then
    redis.call('HSET', KEYS[4], 'createdAt', ARGV[5], 'totalFailures', '0',
            'emailFailures', '0', 'phoneFailures', '0')
    redis.call('PEXPIRE', KEYS[4], WINDOW_MILLIS)
end
local totalFailures = redis.call('HINCRBY', KEYS[4], 'totalFailures', 1)
local exhausted = false
if not emailMatches then
    redis.call('HINCRBY', KEYS[4], 'emailFailures', 1)
    if email[1] ~= false then
        local attempts = redis.call('HINCRBY', KEYS[2], 'attempts', 1)
        if attempts >= CODE_ATTEMPT_LIMIT then
            redis.call('UNLINK', KEYS[2])
            exhausted = true
        end
    end
end
if not phoneMatches then
    redis.call('HINCRBY', KEYS[4], 'phoneFailures', 1)
    if phone[1] ~= false then
        local attempts = redis.call('HINCRBY', KEYS[3], 'attempts', 1)
        if attempts >= CODE_ATTEMPT_LIMIT then
            redis.call('UNLINK', KEYS[3])
            exhausted = true
        end
    end
end
if totalFailures > TOTAL_FAILURE_LIMIT then
    redis.call('SET', KEYS[5], '1', 'EX', BLOCK_SECONDS)
    redis.call('SET', KEYS[6], '1', 'EX', BLOCK_SECONDS)
    redis.call('UNLINK', KEYS[4])
    return {8}
end
if exhausted then
    return {7}
end
return {6}
