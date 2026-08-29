local MAX_FIELDS_PER_CALL = 128

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

if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

local pairs = {'generation', ARGV[1]}
for index = 3, #ARGV, 2 do
    pairs[#pairs + 1] = ARGV[index]
    pairs[#pairs + 1] = ARGV[index + 1]
end
hset_chunked(KEYS[1], pairs)
redis.call('PEXPIREAT', KEYS[1], ARGV[2])
return 1
