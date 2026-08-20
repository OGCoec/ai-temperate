local values = redis.call('HMGET', KEYS[1],
        'state', 'deviceHash', 'ipHash', 'expiresAt', 'absoluteExpiresAt',
        'completionClaim')
if not values[1] then return 1 end
local now = tonumber(ARGV[1])
if now >= tonumber(values[4]) or now >= tonumber(values[5]) then return 2 end
if values[2] ~= ARGV[2] or values[3] ~= ARGV[3] then return 3 end
if values[1] ~= 'READY_TO_COMPLETE' or values[6] == '1' then return 4 end
redis.call('HSET', KEYS[1], 'completionClaim', '1')
return 0
