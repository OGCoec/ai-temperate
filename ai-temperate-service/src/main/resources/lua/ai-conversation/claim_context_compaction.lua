local currentStatus = redis.call('HGET', KEYS[1], 'status')
local currentRevision = tonumber(redis.call('HGET', KEYS[1], 'contextRevision') or '-1')
if currentStatus == 'QUEUED' or currentStatus == 'RUNNING' then
    return 0
end
if (currentStatus == 'COMPLETED' or currentStatus == 'FAILED')
        and currentRevision == tonumber(ARGV[2]) then
    return 0
end

local eventRevision = redis.call('INCR', KEYS[2])
redis.call('PEXPIRE', KEYS[2], ARGV[6])
redis.call('HSET', KEYS[1],
    'schemaVersion', '1',
    'operationPublicId', ARGV[1],
    'contextRevision', ARGV[2],
    'status', 'QUEUED',
    'trigger', ARGV[3],
    'createdAt', ARGV[4],
    'updatedAt', ARGV[4],
    'errorCode', '',
    'retryable', 'false',
    'eventRevision', eventRevision)
redis.call('PEXPIRE', KEYS[1], ARGV[5])
return 1
