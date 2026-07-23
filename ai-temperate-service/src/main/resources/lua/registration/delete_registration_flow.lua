if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end

local access = redis.call('HMGET', KEYS[1],
        'flowCsrfHash', 'deviceHash', 'ipHash', 'challengeHash')
if access[1] ~= ARGV[1]
        or access[2] ~= ARGV[2]
        or access[3] ~= ARGV[3]
        or access[4] ~= ARGV[4] then
    return 3
end

redis.call('UNLINK', KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6])
return 1
