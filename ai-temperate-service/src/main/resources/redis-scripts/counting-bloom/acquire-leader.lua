if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end
local epoch = redis.call('INCR', KEYS[2])
local leaseValue = tostring(epoch) .. ':' .. ARGV[1]
redis.call('SET', KEYS[1], leaseValue, 'PX', ARGV[2])
return epoch
