local provider_key = KEYS[1]
local mode = ARGV[1]
local ttl_millis = tonumber(ARGV[2])
local field_count = tonumber(ARGV[3])
local argument_index = 4

if mode == 'CREATE_IF_MISSING' then
    if redis.call('EXISTS', provider_key) == 1 then
        return 0
    end
else
    -- REPLACE 必须清除旧的可空字段；直接 UNLINK 比 EXISTS 后再 UNLINK 少一次服务端调用。
    redis.call('UNLINK', provider_key)
end
local fields = {}
for index = 1, field_count do
    fields[#fields + 1] = ARGV[argument_index]
    fields[#fields + 1] = ARGV[argument_index + 1]
    argument_index = argument_index + 2
end
if #fields > 0 then
    redis.call('HSET', provider_key, unpack(fields))
end
redis.call('PEXPIRE', provider_key, ttl_millis)
return 1
