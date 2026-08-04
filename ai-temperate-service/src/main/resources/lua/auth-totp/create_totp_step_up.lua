if redis.call('EXISTS', KEYS[1]) == 1 then
    return 1
end
redis.call('HSET', KEYS[1],
    'schemaVersion', '1',
    'userId', ARGV[1],
    'deviceHash', ARGV[2],
    'action', ARGV[3],
    'failedAttempts', '0',
    'createdAt', ARGV[4],
    'expiresAt', ARGV[5])
redis.call('PEXPIRE', KEYS[1], ARGV[6])
return 0
