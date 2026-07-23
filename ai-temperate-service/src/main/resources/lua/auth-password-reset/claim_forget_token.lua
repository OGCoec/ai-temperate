local deviceHash = ARGV[1]
local claimId = ARGV[2]
local values = redis.call('HMGET', KEYS[1], 'userId', 'deviceHash', 'claimed')
if not values[1] then return {1} end
if values[2] ~= deviceHash then return {2} end
if values[3] ~= '0' then return {3} end
redis.call('HSET', KEYS[1], 'claimed', claimId)
return {0, values[1]}
