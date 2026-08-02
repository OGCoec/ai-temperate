if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return -1
end

if redis.call('HLEN', KEYS[1]) > tonumber(ARGV[3]) then
    return -1
end

if redis.call('EXISTS', KEYS[2]) == 1 then
    return 0
end

redis.call('RENAME', KEYS[1], KEYS[2])
redis.call('PEXPIREAT', KEYS[2], ARGV[2])
return 1
