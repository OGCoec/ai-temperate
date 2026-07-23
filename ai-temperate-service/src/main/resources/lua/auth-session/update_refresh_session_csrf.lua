local presentedDeviceHash = ARGV[1]
local newCsrfHash = ARGV[2]
local refreshTtlMillis = tonumber(ARGV[3])
local userIndexPrefix = ARGV[4]
local tokenHash = ARGV[5]

local values = redis.call('HMGET', KEYS[1],
        'userId', 'publicId', 'csrfHash', 'email', 'phone', 'deviceHash')
for index = 1, 6 do
    if not values[index] then
        return {1}
    end
end
if values[6] ~= presentedDeviceHash then
    return {2}
end

local userIndexKey = userIndexPrefix .. values[1]
if redis.call('HEXISTS', userIndexKey, tokenHash) ~= 1 then
    return {4}
end

local serverTime = redis.call('TIME')
local nowMillis = (tonumber(serverTime[1]) * 1000)
        + math.floor(tonumber(serverTime[2]) / 1000)
local expiresAt = nowMillis + refreshTtlMillis

redis.call('HSET', KEYS[1], 'csrfHash', newCsrfHash)
redis.call('PEXPIREAT', KEYS[1], expiresAt)
redis.call('HPEXPIREAT', userIndexKey, expiresAt, 'FIELDS', 1, tokenHash)
redis.call('PEXPIREAT', userIndexKey, expiresAt)

return {0, values[1], values[2], newCsrfHash, values[4], values[5], values[6],
        tostring(expiresAt)}
