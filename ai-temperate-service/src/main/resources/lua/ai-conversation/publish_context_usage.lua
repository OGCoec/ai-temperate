if redis.call('HEXISTS', KEYS[1], 'status') == 0 then
    redis.call('HSET', KEYS[1],
        'schemaVersion', '1',
        'status', 'IDLE',
        'retryable', 'false',
        'errorCode', '')
end
local previousStatus = redis.call('HGET', KEYS[1], 'status')
local previousRevision = tonumber(redis.call('HGET', KEYS[1], 'contextRevision') or '-1')
local nextRevision = tonumber(ARGV[1])
if (previousStatus == 'COMPLETED' or previousStatus == 'FAILED')
        and previousRevision ~= nextRevision then
    redis.call('HSET', KEYS[1],
        'status', 'IDLE',
        'operationPublicId', '',
        'trigger', '',
        'errorCode', '',
        'retryable', 'false')
end
local eventRevision = redis.call('INCR', KEYS[2])
redis.call('PEXPIRE', KEYS[2], ARGV[4])
redis.call('HSET', KEYS[1],
    'contextRevision', ARGV[1],
    'updatedAt', ARGV[2],
    'eventRevision', eventRevision)
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return eventRevision
