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

local fields = {}
local pairs = {}
for index = 3, #ARGV, 2 do
    fields[#fields + 1] = ARGV[index]
    pairs[#pairs + 1] = ARGV[index]
    pairs[#pairs + 1] = ARGV[index + 1]
end
local existing = hmget_chunked(KEYS[1], fields)
local missing = 0
for index = 1, #fields do
    if existing[index] == false then
        missing = missing + 1
    end
end
if redis.call('HLEN', KEYS[1]) + missing > tonumber(ARGV[2]) then
    return -1
end

hset_chunked(KEYS[1], pairs)
return 1
