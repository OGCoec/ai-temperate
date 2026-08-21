local provider_key = KEYS[1]
local mode = ARGV[1]
local ttl_millis = tonumber(ARGV[2])
local field_count = tonumber(ARGV[3])
local argument_index = 4

if mode == 'CREATE_IF_MISSING' and redis.call('EXISTS', provider_key) == 1 then
    return 0
end

redis.call('UNLINK', provider_key)
for index = 1, field_count do
    redis.call('HSET', provider_key, ARGV[argument_index], ARGV[argument_index + 1])
    argument_index = argument_index + 2
end
redis.call('PEXPIRE', provider_key, ttl_millis)
return 1
