local deviceHash = ARGV[1]
local challengeHash = ARGV[2]
local now = tonumber(ARGV[3])
local digest = ARGV[4]
local operationId = ARGV[5]
local codeTtl = tonumber(ARGV[6])
local cooldown = tonumber(ARGV[7])
local maxSends = tonumber(ARGV[8])

local values = redis.call('HMGET', KEYS[1],
        'deviceHash', 'challengeHash', 'humanVerified', 'expiresAt',
        'absoluteExpiresAt', 'lastIssuedAt', 'sendCount')
if not values[1] then return 1 end
if values[1] ~= deviceHash or values[2] ~= challengeHash then return 3 end
if now >= tonumber(values[4]) or now >= tonumber(values[5]) then return 2 end
if values[3] ~= '1' then return 4 end
if values[6] and now - tonumber(values[6]) < cooldown then return 5 end
if tonumber(values[7] or '0') >= maxSends then return 6 end

redis.call('HSET', KEYS[2],
        'digest', digest,
        'operationId', operationId,
        'deliveryStatus', 'PENDING',
        'attempts', '0')
local remaining = math.min(codeTtl, tonumber(values[4]) - now)
redis.call('PEXPIRE', KEYS[2], remaining)
redis.call('HSET', KEYS[1], 'lastIssuedAt', now)
return 0
