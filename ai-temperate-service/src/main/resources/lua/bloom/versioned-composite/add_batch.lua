local control_key = KEYS[1]
local meta = redis.call('HMGET', control_key,
        'state', 'capacity', 'hashCount', 'counterBytes', 'countersPerBucket',
        'maximumElements', 'activeCount', 'buildingCount')
local prefix
if meta[1] == 'ACTIVE' then
    prefix = 'active'
elseif meta[1] == 'BUILDING' or meta[1] == 'READY' then
    prefix = 'building'
else
    return -1
end
if meta[2] ~= ARGV[1] or meta[3] ~= ARGV[2]
        or meta[4] ~= ARGV[3] or meta[5] ~= ARGV[4] then
    return -1
end

local item_count = tonumber(ARGV[5])
local hash_count = tonumber(ARGV[2])
local counter_bytes = tonumber(ARGV[3])
local maximum_elements = tonumber(meta[6])
local current_count = tonumber(prefix == 'active' and meta[7] or meta[8])
if not item_count or item_count < 1 or item_count > 500
        or not hash_count or hash_count < 1 or hash_count > 25
        or (counter_bytes ~= 1 and counter_bytes ~= 2)
        or not maximum_elements or not current_count then
    return -1
end
local counter_type = counter_bytes == 1 and 'u8' or 'u16'
local maximum_counter = counter_bytes == 1 and 255 or 65535
local cursor = 6
local records = {}
local receipt_fields = {}
local receipt_seen = {}

local function register_receipt_field(field)
    if not receipt_seen[field] then
        receipt_seen[field] = true
        receipt_fields[#receipt_fields + 1] = field
    end
end

for item = 1, item_count do
    local record = {
        user_id = ARGV[cursor],
        receipt_field = prefix .. 'Receipt:' .. ARGV[cursor + 1],
        positions = {}
    }
    cursor = cursor + 2
    register_receipt_field(record.receipt_field)
    for index = 1, hash_count do
        local mapping_field = prefix .. 'EmailBucket:' .. ARGV[cursor]
        local offset = tonumber(ARGV[cursor + 1])
        if not offset or offset < 0 then
            return -1
        end
        record.positions[#record.positions + 1] = {
            mapping_field = mapping_field,
            offset = offset
        }
        cursor = cursor + 2
    end
    local phone_present = ARGV[cursor]
    cursor = cursor + 1
    if phone_present == '1' then
        for index = 1, hash_count do
            local mapping_field = prefix .. 'PhoneBucket:' .. ARGV[cursor]
            local offset = tonumber(ARGV[cursor + 1])
            if not offset or offset < 0 then
                return -1
            end
            record.positions[#record.positions + 1] = {
                mapping_field = mapping_field,
                offset = offset
            }
            cursor = cursor + 2
        end
    end
    records[#records + 1] = record
end

local mappings = {}
for start = 1, #receipt_fields, 128 do
    local fields = {}
    local finish = math.min(start + 127, #receipt_fields)
    for index = start, finish do
        fields[#fields + 1] = receipt_fields[index]
    end
    local values = redis.call('HMGET', control_key, unpack(fields))
    for index = 1, #fields do
        if not values[index] then
            return -1
        end
        mappings[fields[index]] = values[index]
    end
end

local receipt_groups = {}
local receipt_identities = {}
for index = 1, #records do
    local record = records[index]
    local receipt_key = mappings[record.receipt_field]
    record.receipt_key = receipt_key
    local identity = receipt_key .. '|' .. record.user_id
    if not receipt_identities[identity] then
        receipt_identities[identity] = true
        local group = receipt_groups[receipt_key]
        if not group then
            group = {users = {}, records = {}}
            receipt_groups[receipt_key] = group
        end
        group.users[#group.users + 1] = record.user_id
        group.records[#group.records + 1] = record
    end
end

local fresh_count = 0
for receipt_key, group in pairs(receipt_groups) do
    for start = 1, #group.users, 128 do
        local users = {}
        local finish = math.min(start + 127, #group.users)
        for index = start, finish do
            users[#users + 1] = group.users[index]
        end
        local membership = redis.call('SMISMEMBER', receipt_key, unpack(users))
        for index = 1, #membership do
            local record = group.records[start + index - 1]
            if tonumber(membership[index]) == 0 then
                record.selected = true
                fresh_count = fresh_count + 1
            end
        end
    end
end
if fresh_count == 0 then
    return 0
end

local mapping_fields = {}
local mapping_seen = {}
for index = 1, #records do
    if records[index].selected then
        for position_index = 1, #records[index].positions do
            local field = records[index].positions[position_index].mapping_field
            if not mapping_seen[field] then
                mapping_seen[field] = true
                mapping_fields[#mapping_fields + 1] = field
            end
        end
    end
end
for start = 1, #mapping_fields, 128 do
    local fields = {}
    local finish = math.min(start + 127, #mapping_fields)
    for index = start, finish do
        fields[#fields + 1] = mapping_fields[index]
    end
    local values = redis.call('HMGET', control_key, unpack(fields))
    for index = 1, #fields do
        if not values[index] then
            return -1
        end
        mappings[fields[index]] = values[index]
    end
end

local bucket_keys = {}
local bucket_seen = {}
for index = 1, #records do
    if records[index].selected then
        for position_index = 1, #records[index].positions do
            local bucket_key = mappings[records[index].positions[position_index].mapping_field]
            if not bucket_seen[bucket_key] then
                bucket_seen[bucket_key] = true
                bucket_keys[#bucket_keys + 1] = bucket_key
            end
        end
    end
end
for start = 1, #bucket_keys, 128 do
    local keys = {}
    local finish = math.min(start + 127, #bucket_keys)
    for index = start, finish do
        keys[#keys + 1] = bucket_keys[index]
    end
    if redis.call('EXISTS', unpack(keys)) ~= #keys then
        return -1
    end
end

local count_field = prefix .. 'Count'
if current_count + fresh_count > maximum_elements then
    redis.call('HSET', control_key,
            'state', 'DEGRADED', 'degradedReason', 'CAPACITY_EXCEEDED')
    return -3
end

local deltas = {}
for index = 1, #records do
    local record = records[index]
    if record.selected then
        for position_index = 1, #record.positions do
            local position = record.positions[position_index]
            local bucket_key = mappings[position.mapping_field]
            local offsets = deltas[bucket_key]
            if not offsets then
                offsets = {}
                deltas[bucket_key] = offsets
            end
            offsets[position.offset] = (offsets[position.offset] or 0) + 1
        end
    end
end

local locations_by_bucket = {}
for bucket_key, offsets in pairs(deltas) do
    local locations = {}
    for offset, delta in pairs(offsets) do
        locations[#locations + 1] = {offset = offset, delta = delta}
    end
    locations_by_bucket[bucket_key] = locations
    for start = 1, #locations, 128 do
        local arguments = {}
        local finish = math.min(start + 127, #locations)
        for index = start, finish do
            arguments[#arguments + 1] = 'GET'
            arguments[#arguments + 1] = counter_type
            arguments[#arguments + 1] = locations[index].offset * 8
        end
        local values = redis.call('BITFIELD_RO', bucket_key, unpack(arguments))
        for index = 1, #values do
            local location = locations[start + index - 1]
            location.current = tonumber(values[index]) or 0
            if location.current + location.delta > maximum_counter then
                redis.call('HSET', control_key,
                        'state', 'DEGRADED', 'degradedReason', 'COUNTER_OVERFLOW')
                return -2
            end
        end
    end
end

for bucket_key, locations in pairs(locations_by_bucket) do
    for start = 1, #locations, 128 do
        local arguments = {}
        local finish = math.min(start + 127, #locations)
        for index = start, finish do
            local location = locations[index]
            arguments[#arguments + 1] = 'SET'
            arguments[#arguments + 1] = counter_type
            arguments[#arguments + 1] = location.offset * 8
            arguments[#arguments + 1] = location.current + location.delta
        end
        redis.call('BITFIELD', bucket_key, unpack(arguments))
    end
end
for receipt_key, group in pairs(receipt_groups) do
    local selected = {}
    for index = 1, #group.records do
        if group.records[index].selected then
            selected[#selected + 1] = group.records[index].user_id
        end
    end
    for start = 1, #selected, 128 do
        local users = {}
        local finish = math.min(start + 127, #selected)
        for index = start, finish do
            users[#users + 1] = selected[index]
        end
        redis.call('SADD', receipt_key, unpack(users))
    end
end

local updated_count = redis.call('HINCRBY', control_key, count_field, fresh_count)
if updated_count >= maximum_elements then
    redis.call('HSET', control_key,
            'state', 'DEGRADED', 'degradedReason', 'CAPACITY_EXCEEDED')
    return -3
end
return fresh_count
