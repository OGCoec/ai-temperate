local values = redis.call('HMGET', KEYS[1],
        'provider', 'platform', 'interactionMode', 'state', 'deviceHash', 'ipHash',
        'nonceHash', 'nativeConsumed', 'expiresAt', 'absoluteExpiresAt')
if not values[1] then return 1 end
local now = tonumber(ARGV[1])
if now >= tonumber(values[9]) or now >= tonumber(values[10]) then return 2 end
if values[1] ~= 'GOOGLE' or values[2] ~= 'ANDROID'
        or values[3] ~= 'GOOGLE_NATIVE' or values[4] ~= 'PROVIDER_PENDING' then return 4 end
if values[5] ~= ARGV[2] or values[6] ~= ARGV[3] then return 3 end
if values[7] ~= ARGV[4] or values[8] ~= '0' then return 5 end
redis.call('HSET', KEYS[1], 'nativeConsumed', '1')
return 0
