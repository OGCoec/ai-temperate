if redis.call('EXISTS', KEYS[1]) == 1 then
    return 1
end
if redis.call('EXISTS', KEYS[2]) == 1 then
    return 1
end
return 0
