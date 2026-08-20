local values = redis.call('HMGET', KEYS[1], 'provider', 'state', 'expiresAt', 'absoluteExpiresAt')
if not values[1] then return 1 end
local now = tonumber(ARGV[1])
if now >= tonumber(values[3]) or now >= tonumber(values[4]) then return 2 end
if values[1] ~= ARGV[2] then return 3 end
if values[2] ~= 'PROVIDER_PENDING' then return 4 end
local nextState = ARGV[8] == '1' and 'PHONE_REQUIRED' or 'READY_TO_COMPLETE'
local renewedExpiresAt = math.min(now + tonumber(ARGV[9]), tonumber(values[4]))
redis.call('HSET', KEYS[1],
        'providerSubject', ARGV[3],
        'verifiedEmail', ARGV[4],
        'proofType', ARGV[5],
        'existingIdentityId', ARGV[6],
        'phoneRequired', ARGV[8],
        'expiresAt', renewedExpiresAt,
        'state', nextState)
redis.call('PEXPIRE', KEYS[1], renewedExpiresAt - now)
return 0
