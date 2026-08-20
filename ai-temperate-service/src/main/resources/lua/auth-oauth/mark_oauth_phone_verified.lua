local values = redis.call('HMGET', KEYS[1],
        'state', 'deviceHash', 'ipHash', 'phoneFlowId', 'phoneChallengeId',
        'lockedPhone', 'expiresAt', 'absoluteExpiresAt')
if not values[1] then return 1 end
local now = tonumber(ARGV[1])
if now >= tonumber(values[7]) or now >= tonumber(values[8]) then return 2 end
if values[2] ~= ARGV[2] or values[3] ~= ARGV[3]
        or values[4] ~= ARGV[4] or values[5] ~= ARGV[5]
        or values[6] ~= ARGV[6] then return 3 end
if values[1] ~= 'CODE_READY' then return 4 end
redis.call('HSET', KEYS[1], 'phoneVerified', '1', 'state', 'READY_TO_COMPLETE')
return 0
