local presentedDeviceHash = ARGV[1]
local userIndexPrefix = ARGV[2]
local tokenHash = ARGV[3]

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

local sessionTtl = redis.call('PTTL', KEYS[1])
local indexTtl = redis.call('PTTL', userIndexKey)
local fieldTtlValues = redis.call('HPTTL', userIndexKey, 'FIELDS', 1, tokenHash)
local fieldTtl = fieldTtlValues[1]
if sessionTtl == -1 or indexTtl == -1 or fieldTtl == -1 then
    return {6}
end
if sessionTtl <= 0 or indexTtl <= 0 or fieldTtl <= 0 then
    return {1}
end

local serverTime = redis.call('TIME')
local nowMillis = (tonumber(serverTime[1]) * 1000)
        + math.floor(tonumber(serverTime[2]) / 1000)
local expiresAt = nowMillis + math.min(sessionTtl, indexTtl, fieldTtl)

return {0, values[1], values[2], values[3], values[4], values[5], values[6],
        tostring(expiresAt)}
