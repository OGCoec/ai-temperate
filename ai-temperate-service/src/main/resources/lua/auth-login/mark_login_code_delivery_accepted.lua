local operationId = ARGV[1]
local maxSends = tonumber(ARGV[2])
if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
local values = redis.call('HMGET', KEYS[2], 'operationId', 'deliveryStatus')
if values[1] ~= operationId then return 0 end
if values[2] == 'SUCCESS' then return 1 end
if values[2] ~= 'PENDING' and values[2] ~= 'DELIVERING' then return 0 end
local current = tonumber(redis.call('HGET', KEYS[1], 'sendCount') or '0')
if current >= maxSends then redis.call('UNLINK', KEYS[2]); return 2 end
redis.call('HSET', KEYS[2],
        'deliveryStatus', 'SUCCESS',
        'providerMessageId', ARGV[3],
        'providerStatus', ARGV[4],
        'acceptedAt', redis.call('TIME')[1])
redis.call('HDEL', KEYS[2], 'activeMessageId', 'unknownReason', 'unknownAt')
redis.call('HINCRBY', KEYS[1], 'sendCount', 1)
return 1
