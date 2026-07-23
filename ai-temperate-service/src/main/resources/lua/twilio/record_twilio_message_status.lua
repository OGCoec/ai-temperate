if redis.call('EXISTS', KEYS[1]) == 0 and ARGV[7] ~= '1' then
    return 0
end
local currentRank = tonumber(redis.call('HGET', KEYS[1], 'statusRank') or '0')
local incomingRank = tonumber(ARGV[6])
if incomingRank < currentRank then
    return 0
end
redis.call('HSET', KEYS[1],
        'providerStatus', ARGV[2],
        'statusRank', ARGV[6],
        'updatedAt', ARGV[4])
if ARGV[1] ~= '' then
    redis.call('HSET', KEYS[1], 'operationId', ARGV[1])
end
if ARGV[3] ~= '' then
    redis.call('HSET', KEYS[1], 'providerErrorCode', ARGV[3])
else
    redis.call('HDEL', KEYS[1], 'providerErrorCode')
end
redis.call('EXPIRE', KEYS[1], ARGV[5])
return 1
