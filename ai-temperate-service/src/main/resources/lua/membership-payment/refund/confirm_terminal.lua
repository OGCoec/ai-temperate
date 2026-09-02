local state = redis.call('HGET', KEYS[1], 'state')
if state == 'TERMINAL' and redis.call('HGET', KEYS[1], 'messageId') == ARGV[1] then
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
    return 1
end
if state ~= 'TERMINAL_PUBLISH_PENDING'
        or redis.call('HGET', KEYS[1], 'messageId') ~= ARGV[1] then
    return 0
end
redis.call('HSET', KEYS[1], 'state', 'TERMINAL')
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 1
