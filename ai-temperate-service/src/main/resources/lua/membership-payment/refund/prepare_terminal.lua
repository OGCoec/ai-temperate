local state = redis.call('HGET', KEYS[1], 'state')
local attempt = redis.call('HGET', KEYS[1], 'attemptNo')
if state == 'TERMINAL_PUBLISH_PENDING'
        and attempt == ARGV[1]
        and redis.call('HGET', KEYS[1], 'messageId') == ARGV[2]
        and redis.call('HGET', KEYS[1], 'terminalOutcome') == ARGV[3] then
    redis.call('PEXPIRE', KEYS[1], ARGV[5])
    return 1
end
if state ~= 'ATTEMPTING' or attempt ~= ARGV[1] then
    return 0
end
local sourceMessageId = redis.call('HGET', KEYS[1], 'messageId') or ''
redis.call('HSET', KEYS[1],
        'state', 'TERMINAL_PUBLISH_PENDING',
        'sourceMessageId', sourceMessageId,
        'messageId', ARGV[2],
        'terminalOutcome', ARGV[3],
        'safeReason', ARGV[4])
redis.call('HDEL', KEYS[1], 'nextAttemptNo')
redis.call('PEXPIRE', KEYS[1], ARGV[5])
return 1
