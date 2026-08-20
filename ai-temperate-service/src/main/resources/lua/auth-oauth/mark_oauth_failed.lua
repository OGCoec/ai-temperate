local values = redis.call('HMGET', KEYS[1], 'state', 'expiresAt', 'absoluteExpiresAt')
if not values[1] then return 1 end
local now = tonumber(ARGV[1])
if now >= tonumber(values[2]) or now >= tonumber(values[3]) then return 2 end
if values[1] == 'AUTHENTICATED' or values[1] == 'TOTP_REQUIRED' then return 4 end
redis.call('HSET', KEYS[1], 'state', 'FAILED')
return 0
