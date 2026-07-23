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

local storedClaim = redis.call('HGET', KEYS[1], 'completionClaim')
if storedClaim == false or storedClaim ~= ARGV[5] then
    return 0
end

redis.call('HDEL', KEYS[1], 'completionClaim')
return 1
