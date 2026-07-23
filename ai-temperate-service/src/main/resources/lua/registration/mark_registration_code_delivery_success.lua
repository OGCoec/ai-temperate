local ALLOWED_SENDS = 5
local WINDOW_MILLIS = 300000
local BLOCK_SECONDS = 7200

if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
    return 0
end
local access = redis.call('HMGET', KEYS[1],
        'flowCsrfHash', 'deviceHash', 'ipHash', 'challengeHash')
if access[1] ~= ARGV[1] or access[2] ~= ARGV[2]
        or access[3] ~= ARGV[3] or access[4] ~= ARGV[4] then
    return 3
end
local operationId = redis.call('HGET', KEYS[2], 'sendOperationId')
local deliveryStatus = redis.call('HGET', KEYS[2], 'deliveryStatus')
if operationId == false or operationId ~= ARGV[5]
        or (deliveryStatus ~= 'PENDING' and deliveryStatus ~= 'DELIVERING') then
    return 0
end

local windowMillis = tonumber(ARGV[7])
local blockSeconds = tonumber(ARGV[8])
if windowMillis ~= WINDOW_MILLIS or blockSeconds ~= BLOCK_SECONDS then
    return redis.error_reply('invalid delivery success boundaries')
end
if redis.call('EXISTS', KEYS[3]) == 0 then
    redis.call('HSET', KEYS[3], 'createdAt', redis.call('TIME')[1])
    redis.call('PEXPIRE', KEYS[3], windowMillis)
end
redis.call('HSET', KEYS[2], 'deliveryStatus', 'SUCCESS')
redis.call('HDEL', KEYS[2], 'activeMessageId')
local count = redis.call('HINCRBY', KEYS[3], ARGV[6], 1)
if count > ALLOWED_SENDS then
    redis.call('SET', KEYS[4], '1', 'EX', blockSeconds)
    redis.call('SET', KEYS[5], '1', 'EX', blockSeconds)
    redis.call('UNLINK', KEYS[3])
    return 2
end
return 1
