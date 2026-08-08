if redis.call('HGET', KEYS[1], 'operationPublicId') ~= ARGV[1] then
    return 0
end

local eventRevision = redis.call('INCR', KEYS[2])
redis.call('PEXPIRE', KEYS[2], ARGV[9])
redis.call('HSET', KEYS[1],
    'status', ARGV[2],
    'updatedAt', ARGV[4],
    'errorCode', ARGV[7],
    'retryable', ARGV[8],
    'eventRevision', eventRevision)
if ARGV[6] ~= '' then
    redis.call('HSET', KEYS[1], 'contextRevision', ARGV[6])
end
redis.call('PEXPIRE', KEYS[1], ARGV[5])
return eventRevision
