local buildFenced = #ARGV >= 3 and ARGV[#ARGV - 2] == 'BUILD_FENCE'
local bucketKeyEnd = #KEYS
local argumentEnd = #ARGV
if buildFenced then
    if redis.call('GET', KEYS[#KEYS]) ~= ARGV[#ARGV - 1]
            or redis.call('HGET', KEYS[1], 'build_fence') ~= ARGV[#ARGV] then
        return -4
    end
    bucketKeyEnd = #KEYS - 1
    argumentEnd = #ARGV - 3
end

local state = redis.call('HGET', KEYS[1], 'state')
if state == false then
    return -3
end
if (state ~= 'BUILDING' and state ~= 'READY' and state ~= 'ACTIVE' and state ~= 'DEGRADED')
        or redis.call('HGET', KEYS[1], 'capacity') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'hash_count') ~= ARGV[3]
        or redis.call('HGET', KEYS[1], 'counter_bytes') ~= ARGV[4]
        or redis.call('HGET', KEYS[1], 'counters_per_bucket') ~= ARGV[5]
        or redis.call('HGET', KEYS[1], 'bucket_count') ~= ARGV[6] then
    redis.call('HSET', KEYS[1], 'state', 'DEGRADED', 'reason', 'metadata_invalid')
    return -3
end

local identifier = ARGV[1]
if redis.call('SISMEMBER', KEYS[2], '__ait_counting_bloom_receipt__') == 0 then
    redis.call('HSET', KEYS[1], 'state', 'DEGRADED', 'reason', 'receipt_missing')
    return -3
end
if redis.call('SISMEMBER', KEYS[2], identifier) == 1 then
    return 2
end

local counterBytes = tonumber(ARGV[4])
local capacity = tonumber(ARGV[2])
local countersPerBucket = tonumber(ARGV[5])
for keyIndex = 3, bucketKeyEnd do
    local bucketNumber = keyIndex - 3
    local remaining = capacity - bucketNumber * countersPerBucket
    local expectedLength = math.min(countersPerBucket, remaining) * counterBytes
    if expectedLength <= 0 or redis.call('STRLEN', KEYS[keyIndex]) ~= expectedLength then
        redis.call('HSET', KEYS[1], 'state', 'DEGRADED', 'reason', 'bucket_length_invalid')
        return -3
    end
end
local maximumCounter = counterBytes == 1 and 255 or 65535
local function readCounter(key, offset)
    if counterBytes == 1 then
        local raw = redis.call('GETRANGE', key, offset, offset)
        return (raw == false or string.len(raw) == 0) and 0 or string.byte(raw)
    end
    local raw = redis.call('GETRANGE', key, offset, offset + 1)
    return (raw == false or string.len(raw) < 2)
            and 0 or (string.byte(raw, 1) * 256 + string.byte(raw, 2))
end
local function writeCounter(key, offset, value)
    if counterBytes == 1 then
        redis.call('SETRANGE', key, offset, string.char(value))
        return
    end
    redis.call('SETRANGE', key, offset,
            string.char(math.floor(value / 256), value % 256))
end

for index = 7, argumentEnd, 2 do
    local bucket_key_index = tonumber(ARGV[index])
    local offset = tonumber(ARGV[index + 1])
    local counter = readCounter(KEYS[bucket_key_index], offset)
    if counter >= maximumCounter then
        redis.call('HSET', KEYS[1], 'state', 'DEGRADED', 'reason', 'counter_overflow')
        return -1
    end
end

for index = 7, argumentEnd, 2 do
    local bucket_key_index = tonumber(ARGV[index])
    local offset = tonumber(ARGV[index + 1])
    local counter = readCounter(KEYS[bucket_key_index], offset)
    writeCounter(KEYS[bucket_key_index], offset, counter + 1)
end
redis.call('SADD', KEYS[2], identifier)
redis.call('HINCRBY', KEYS[1], 'element_count', 1)
return 1
