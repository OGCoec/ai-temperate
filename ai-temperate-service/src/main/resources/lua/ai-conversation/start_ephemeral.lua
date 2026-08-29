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

if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end

local ordinal = tonumber(redis.call('HGET', KEYS[1], 'sequence:ephemeral') or '0') + 1
local root = 'ephemeral:' .. ordinal
local meta = cjson.encode({
    schemaVersion = 2,
    state = 'STREAMING',
    ordinal = ordinal,
    usagePublicId = ARGV[2],
    createdAt = ARGV[3],
    estimatedTokens = 0
})
local fields = {'sequence:ephemeral', root .. ':meta'}
local pairs = {'sequence:ephemeral', tostring(ordinal), root .. ':meta', meta}
local write_count = tonumber(ARGV[4])
local argument_index = 6
for ignored = 1, write_count do
    local field = root .. ':' .. ARGV[argument_index]
    fields[#fields + 1] = field
    pairs[#pairs + 1] = field
    pairs[#pairs + 1] = ARGV[argument_index + 1]
    argument_index = argument_index + 2
end

local existing = hmget_chunked(KEYS[1], fields)
local missing = 0
for index = 1, #fields do
    if existing[index] == false then
        missing = missing + 1
    end
end
if redis.call('HLEN', KEYS[1]) + missing > tonumber(ARGV[5]) then
    return -1
end

hset_chunked(KEYS[1], pairs)
return ordinal
