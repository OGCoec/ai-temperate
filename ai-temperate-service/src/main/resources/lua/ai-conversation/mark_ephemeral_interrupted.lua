if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end

local field = 'ephemeral:' .. ARGV[2] .. ':meta'
local raw = redis.call('HGET', KEYS[1], field)
if raw == false then
    return -1
end

local meta = cjson.decode(raw)
meta.state = 'INTERRUPTED'
redis.call('HSET', KEYS[1], field, cjson.encode(meta))
return 1
