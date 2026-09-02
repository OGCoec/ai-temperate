local state = redis.call('HGET', KEYS[1], 'state')
if state == 'RETRY_WAIT'
        and redis.call('HGET', KEYS[1], 'messageId') == ARGV[1]
        and redis.call('HGET', KEYS[1], 'attemptNo') == ARGV[2] then
    redis.call('PEXPIRE', KEYS[1], ARGV[3])
    return 1
end
if state ~= 'RETRY_PUBLISH_PENDING'
        or redis.call('HGET', KEYS[1], 'messageId') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'nextAttemptNo') ~= ARGV[2] then
    return 0
end
redis.call('HSET', KEYS[1], 'state', 'RETRY_WAIT', 'attemptNo', ARGV[2])
redis.call('HDEL', KEYS[1], 'nextAttemptNo', 'sourceMessageId')
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 1
