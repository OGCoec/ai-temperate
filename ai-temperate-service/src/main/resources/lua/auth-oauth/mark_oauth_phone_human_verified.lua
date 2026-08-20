local values = redis.call('HMGET', KEYS[1],
        'state', 'deviceHash', 'ipHash', 'phoneFlowId', 'phoneChallengeId',
        'expiresAt', 'absoluteExpiresAt')
if not values[1] then return 1 end
local now = tonumber(ARGV[1])
if now >= tonumber(values[6]) or now >= tonumber(values[7]) then return 2 end
if values[2] ~= ARGV[2] or values[3] ~= ARGV[3]
        or values[4] ~= ARGV[4] or values[5] ~= ARGV[5] then return 3 end
if values[1] == 'CODE_READY' then return 0 end
if values[1] ~= 'HUMAN_VERIFICATION_REQUIRED' then return 4 end
redis.call('HSET', KEYS[1], 'state', 'CODE_READY')
return 0
