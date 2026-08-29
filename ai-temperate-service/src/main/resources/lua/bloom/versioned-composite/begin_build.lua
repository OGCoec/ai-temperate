local previous = redis.call('HGET', KEYS[1], 'activeGeneration')
        or redis.call('HGET', KEYS[1], 'previousActiveGeneration')
redis.call('DEL', KEYS[1])
for start = 1, #ARGV, 256 do
    local fields = {}
    local finish = math.min(start + 255, #ARGV)
    for index = start, finish do
        fields[#fields + 1] = ARGV[index]
    end
    redis.call('HSET', KEYS[1], unpack(fields))
end
if previous then
    redis.call('HSET', KEYS[1], 'previousActiveGeneration', previous)
    return previous
end
return ''
