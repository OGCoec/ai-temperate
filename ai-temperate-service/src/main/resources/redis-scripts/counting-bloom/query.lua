local state = redis.call('HGET', KEYS[1], 'state')
if state ~= 'ACTIVE' then
    return 2
end
if redis.call('HGET', KEYS[1], 'capacity') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'hash_count') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'counter_bytes') ~= ARGV[3]
        or redis.call('HGET', KEYS[1], 'counters_per_bucket') ~= ARGV[4]
        or redis.call('HGET', KEYS[1], 'bucket_count') ~= ARGV[5] then
    redis.call('HSET', KEYS[1], 'state', 'DEGRADED', 'reason', 'metadata_invalid')
    return 2
end

local counterBytes = tonumber(ARGV[3])
local capacity = tonumber(ARGV[1])
local countersPerBucket = tonumber(ARGV[4])
for keyIndex = 2, #KEYS do
    local bucketNumber = keyIndex - 2
    local remaining = capacity - bucketNumber * countersPerBucket
    local expectedLength = math.min(countersPerBucket, remaining) * counterBytes
    if expectedLength <= 0 or redis.call('STRLEN', KEYS[keyIndex]) ~= expectedLength then
        redis.call('HSET', KEYS[1], 'state', 'DEGRADED', 'reason', 'bucket_length_invalid')
        return 2
    end
end
local function readCounter(key, offset)
    if counterBytes == 1 then
        local raw = redis.call('GETRANGE', key, offset, offset)
        return (raw == false or string.len(raw) == 0) and 0 or string.byte(raw)
    end
    local raw = redis.call('GETRANGE', key, offset, offset + 1)
    return (raw == false or string.len(raw) < 2)
            and 0 or (string.byte(raw, 1) * 256 + string.byte(raw, 2))
end

for index = 6, #ARGV, 2 do
    local bucket_key_index = tonumber(ARGV[index])
    local offset = tonumber(ARGV[index + 1])
    if readCounter(KEYS[bucket_key_index], offset) == 0 then
        return 0
    end
end
return 1
