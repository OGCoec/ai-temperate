local tokenHash = ARGV[1]
local presentedDeviceHash = ARGV[2]
local presentedCsrfHash = ARGV[3]
local userIndexPrefix = ARGV[4]
local absoluteRevokeBound = tonumber(ARGV[5])

local values = redis.call('HMGET', KEYS[1], 'userId', 'deviceHash', 'csrfHash')
if not values[1] or not values[2] or not values[3] then
    return 0
end
if values[2] ~= presentedDeviceHash then
    return -2
end
if values[3] ~= presentedCsrfHash then
    return -3
end

local userIndexKey = userIndexPrefix .. values[1]
if redis.call('HLEN', userIndexKey) > absoluteRevokeBound then
    return -4
end

redis.call('UNLINK', KEYS[1])
redis.call('HDEL', userIndexKey, tokenHash)

local fields = redis.call('HKEYS', userIndexKey)
if #fields == 0 then
    redis.call('UNLINK', userIndexKey)
    return 1
end

local ttlCommand = {'HPTTL', userIndexKey, 'FIELDS', #fields}
for _, field in ipairs(fields) do
    table.insert(ttlCommand, field)
end
local ttls = redis.call(unpack(ttlCommand))
local maxTtl = 0
for _, ttl in ipairs(ttls) do
    if ttl > maxTtl then
        maxTtl = ttl
    end
end
if maxTtl > 0 then
    redis.call('PEXPIRE', userIndexKey, maxTtl)
else
    redis.call('UNLINK', userIndexKey)
end
return 1
