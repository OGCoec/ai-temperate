local values = redis.call('HMGET', KEYS[1],
        'state', 'deviceHash', 'ipHash', 'completionClaim')
if not values[1] then return 1 end
if values[2] ~= ARGV[1] or values[3] ~= ARGV[2] then return 3 end
if values[1] ~= 'READY_TO_COMPLETE' or values[4] ~= '1' then return 4 end
redis.call('HDEL', KEYS[1], 'completionClaim')
return 0
