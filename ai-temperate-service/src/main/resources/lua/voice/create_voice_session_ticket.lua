local user_count = redis.call('INCR', KEYS[1])
if user_count == 1 then
    redis.call('PEXPIRE', KEYS[1], ARGV[3])
end

local device_count = redis.call('INCR', KEYS[2])
if device_count == 1 then
    redis.call('PEXPIRE', KEYS[2], ARGV[3])
end

local limit = tonumber(ARGV[4])
if user_count > limit or device_count > limit then
    return 1
end

local created = redis.call('SET', KEYS[3], ARGV[1], 'NX', 'PX', ARGV[2])
if not created then
    return 2
end
return 0
