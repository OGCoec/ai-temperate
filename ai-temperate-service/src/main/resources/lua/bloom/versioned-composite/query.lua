local control_key = KEYS[1]
local meta = redis.call('HMGET', control_key,
        'state', 'capacity', 'hashCount', 'counterBytes', 'countersPerBucket')
if meta[1] ~= 'ACTIVE' then
    return -1
end
if meta[2] ~= ARGV[1] or meta[3] ~= ARGV[2]
        or meta[4] ~= ARGV[3] or meta[5] ~= ARGV[4] then
    return -2
end

local counter_bytes = tonumber(ARGV[3])
local counter_type = counter_bytes == 1 and 'u8' or 'u16'
local field_prefix = ARGV[5]
local position_count = tonumber(ARGV[6])
local cursor = 7
local mapping_fields = {}
local mapping_seen = {}
local positions = {}

for index = 1, position_count do
    local mapping_field = field_prefix .. ARGV[cursor]
    if not mapping_seen[mapping_field] then
        mapping_seen[mapping_field] = true
        mapping_fields[#mapping_fields + 1] = mapping_field
    end
    positions[#positions + 1] = {
        mapping_field = mapping_field,
        offset = tonumber(ARGV[cursor + 1])
    }
    cursor = cursor + 2
end

local mappings = {}
for start = 1, #mapping_fields, 128 do
    local fields = {}
    local finish = math.min(start + 127, #mapping_fields)
    for index = start, finish do
        fields[#fields + 1] = mapping_fields[index]
    end
    local values = redis.call('HMGET', control_key, unpack(fields))
    for index = 1, #fields do
        if not values[index] then
            return -2
        end
        mappings[fields[index]] = values[index]
    end
end

local bucket_keys = {}
local bucket_seen = {}
local grouped = {}
for index = 1, #positions do
    local bucket_key = mappings[positions[index].mapping_field]
    if not bucket_seen[bucket_key] then
        bucket_seen[bucket_key] = true
        bucket_keys[#bucket_keys + 1] = bucket_key
        grouped[bucket_key] = {}
    end
    grouped[bucket_key][#grouped[bucket_key] + 1] = positions[index].offset
end

for start = 1, #bucket_keys, 128 do
    local keys = {}
    local finish = math.min(start + 127, #bucket_keys)
    for index = start, finish do
        keys[#keys + 1] = bucket_keys[index]
    end
    if redis.call('EXISTS', unpack(keys)) ~= #keys then
        return -2
    end
end

for bucket_key, offsets in pairs(grouped) do
    for start = 1, #offsets, 128 do
        local arguments = {}
        local finish = math.min(start + 127, #offsets)
        for index = start, finish do
            arguments[#arguments + 1] = 'GET'
            arguments[#arguments + 1] = counter_type
            arguments[#arguments + 1] = offsets[index] * 8
        end
        local values = redis.call('BITFIELD_RO', bucket_key, unpack(arguments))
        for index = 1, #values do
            if tonumber(values[index]) == 0 then
                return 0
            end
        end
    end
end
return 1
