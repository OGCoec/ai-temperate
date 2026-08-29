local meta = redis.call('HMGET', KEYS[1],
        'state', 'buildingGeneration', 'bucketCount', 'receiptShards', 'buildingCount')
if meta[1] ~= 'READY' or meta[2] ~= ARGV[1] then
    return 0
end
local bucket_count = tonumber(meta[3])
local receipt_shards = tonumber(meta[4])
if not bucket_count or not receipt_shards then
    return -1
end

local source_fields = {}
local target_fields = {}
for number = 0, bucket_count - 1 do
    local suffix = string.format('%04d', number)
    source_fields[#source_fields + 1] = 'buildingEmailBucket:' .. suffix
    target_fields[#target_fields + 1] = 'activeEmailBucket:' .. suffix
    source_fields[#source_fields + 1] = 'buildingPhoneBucket:' .. suffix
    target_fields[#target_fields + 1] = 'activePhoneBucket:' .. suffix
end
for number = 0, receipt_shards - 1 do
    local suffix = string.format('%04d', number)
    source_fields[#source_fields + 1] = 'buildingReceipt:' .. suffix
    target_fields[#target_fields + 1] = 'activeReceipt:' .. suffix
end

local source_values = {}
for start = 1, #source_fields, 128 do
    local fields = {}
    local finish = math.min(start + 127, #source_fields)
    for index = start, finish do
        fields[#fields + 1] = source_fields[index]
    end
    local values = redis.call('HMGET', KEYS[1], unpack(fields))
    for index = 1, #values do
        if not values[index] then
            return -1
        end
        source_values[start + index - 1] = values[index]
    end
end

for start = 1, #target_fields, 128 do
    local fields = {}
    local finish = math.min(start + 127, #target_fields)
    for index = start, finish do
        fields[#fields + 1] = target_fields[index]
        fields[#fields + 1] = source_values[index]
    end
    redis.call('HSET', KEYS[1], unpack(fields))
end
redis.call('HSET', KEYS[1],
        'activeGeneration', ARGV[1],
        'activeCount', meta[5] or '0',
        'state', 'ACTIVE')
return 1
