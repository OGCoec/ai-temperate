local MAX_FIELDS_PER_CALL = 128

local function hmget_chunked(key, fields)
    local result = {}
    for start = 1, #fields, MAX_FIELDS_PER_CALL do
        local arguments = {}
        local finish = math.min(start + MAX_FIELDS_PER_CALL - 1, #fields)
        for index = start, finish do
            arguments[#arguments + 1] = fields[index]
        end
        local values = redis.call('HMGET', key, unpack(arguments))
        for index = 1, #values do
            result[start + index - 1] = values[index]
        end
    end
    return result
end

local function hset_chunked(key, pairs)
    for start = 1, #pairs, MAX_FIELDS_PER_CALL * 2 do
        local arguments = {}
        local finish = math.min(start + MAX_FIELDS_PER_CALL * 2 - 1, #pairs)
        for index = start, finish do
            arguments[#arguments + 1] = pairs[index]
        end
        redis.call('HSET', key, unpack(arguments))
    end
end

local function hdel_chunked(key, fields)
    for start = 1, #fields, MAX_FIELDS_PER_CALL do
        local arguments = {}
        local finish = math.min(start + MAX_FIELDS_PER_CALL - 1, #fields)
        for index = start, finish do
            arguments[#arguments + 1] = fields[index]
        end
        redis.call('HDEL', key, unpack(arguments))
    end
end

local header = redis.call('HMGET', KEYS[1], 'generation', 'meta')
if header[1] ~= ARGV[1] then
    return 0
end
if header[2] == false then
    return -1
end
local current_meta = cjson.decode(header[2])
if tonumber(current_meta.contextRevision or 0) ~= tonumber(ARGV[2]) then
    return 2
end

local maximumFields = tonumber(ARGV[4])
local delete_count = tonumber(ARGV[5])
local delete_fields = {}
local delete_set = {}
local affected = {'meta'}
local affected_seen = {meta = true}
local index = 6
for ignored = 1, delete_count do
    local field = ARGV[index]
    if field == 'generation' then
        return -1
    end
    if not delete_set[field] then
        delete_set[field] = true
        delete_fields[#delete_fields + 1] = field
        if not affected_seen[field] then
            affected_seen[field] = true
            affected[#affected + 1] = field
        end
    end
    index = index + 1
end

local write_count = tonumber(ARGV[index])
index = index + 1
local write_pairs = {'meta', ARGV[3]}
local write_set = {meta = true}
for ignored = 1, write_count do
    local field = ARGV[index]
    if write_set[field] then
        return -1
    end
    write_set[field] = true
    write_pairs[#write_pairs + 1] = field
    write_pairs[#write_pairs + 1] = ARGV[index + 1]
    if not affected_seen[field] then
        affected_seen[field] = true
        affected[#affected + 1] = field
    end
    index = index + 2
end

local existing = hmget_chunked(KEYS[1], affected)
local delta = 0
for affected_index = 1, #affected do
    local field = affected[affected_index]
    local current_exists = existing[affected_index] ~= false
    local final_exists = write_set[field] or (current_exists and not delete_set[field])
    if final_exists and not current_exists then
        delta = delta + 1
    elseif current_exists and not final_exists then
        delta = delta - 1
    end
end
if redis.call('HLEN', KEYS[1]) + delta > maximumFields then
    return -1
end

hdel_chunked(KEYS[1], delete_fields)
hset_chunked(KEYS[1], write_pairs)
return 1
