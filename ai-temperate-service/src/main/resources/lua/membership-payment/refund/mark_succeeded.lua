local state = redis.call('HGET', KEYS[1], 'state')
local attempt = redis.call('HGET', KEYS[1], 'attemptNo')
if state == 'SUCCEEDED' then
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
    return 1
end
if state ~= 'ATTEMPTING' or attempt ~= ARGV[1] then
    return 0
end
redis.call('HSET', KEYS[1], 'state', 'SUCCEEDED')
redis.call('HDEL', KEYS[1], 'messageId', 'nextAttemptNo', 'terminalOutcome', 'safeReason')
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 1
