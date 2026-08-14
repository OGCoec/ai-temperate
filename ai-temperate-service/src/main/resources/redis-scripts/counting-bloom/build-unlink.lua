if redis.call('GET', KEYS[1]) ~= ARGV[1] then
    return -4
end
if #KEYS <= 1 then
    return 0
end
return redis.call('UNLINK', unpack(KEYS, 2, #KEYS))
