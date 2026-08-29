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

if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end

local root = 'ephemeral:' .. ARGV[2]
local meta_field = root .. ':meta'
local raw_values = redis.call('HMGET', KEYS[1], meta_field, 'meta')
if raw_values[1] == false or raw_values[2] == false then
    return -1
end

local context_meta = cjson.decode(raw_values[2])
local meta = cjson.decode(raw_values[1])
local previous_count = tonumber(meta.assistantChunkCount or 0)
local previous_tokens = tonumber(meta.estimatedTokens or 0)
local requested_tokens = tonumber(ARGV[4])
local next_tokens = ARGV[3] == 'USER_STOP' and requested_tokens or 0
local updated_total = tonumber(context_meta.estimatedContextTokens or 0)
        - previous_tokens + next_tokens
if updated_total < 0 then
    return -1
end

local write_count = tonumber(ARGV[6])
local maximumFields = tonumber(ARGV[7])
local old_fields = {}
local old_set = {}
local new_set = {}
local affected = {}
local affected_seen = {}
for index = 0, previous_count - 1 do
    local field = root .. ':assistant:' .. string.format('%08d', index)
    old_fields[#old_fields + 1] = field
    old_set[field] = true
    if not affected_seen[field] then
        affected_seen[field] = true
        affected[#affected + 1] = field
    end
end
local pairs = {}
for index = 0, write_count - 1 do
    local field = root .. ':assistant:' .. string.format('%08d', index)
    new_set[field] = true
    pairs[#pairs + 1] = field
    pairs[#pairs + 1] = ARGV[8 + index]
    if not affected_seen[field] then
        affected_seen[field] = true
        affected[#affected + 1] = field
    end
end

local existing = hmget_chunked(KEYS[1], affected)
local delta = 0
for index = 1, #affected do
    local field = affected[index]
    local current_exists = existing[index] ~= false
    local final_exists = new_set[field]
            or (current_exists and not old_set[field])
    if final_exists and not current_exists then
        delta = delta + 1
    elseif current_exists and not final_exists then
        delta = delta - 1
    end
end
if redis.call('HLEN', KEYS[1]) + delta > maximumFields then
    return -1
end

meta.state = 'INTERRUPTED'
meta.interruptionSource = ARGV[3]
meta.assistantChunkCount = write_count
meta.estimatedTokens = next_tokens
context_meta.estimatedContextTokens = updated_total
context_meta.contextRevision = tonumber(context_meta.contextRevision or 0) + 1
context_meta.updatedAt = ARGV[5]
pairs[#pairs + 1] = meta_field
pairs[#pairs + 1] = cjson.encode(meta)
pairs[#pairs + 1] = 'meta'
pairs[#pairs + 1] = cjson.encode(context_meta)

hdel_chunked(KEYS[1], old_fields)
hset_chunked(KEYS[1], pairs)
return 1
