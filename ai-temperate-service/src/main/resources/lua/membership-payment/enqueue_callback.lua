local idempotency_key = KEYS[1]
local order_idempotency_key = KEYS[2]
local provider_trade_idempotency_key = KEYS[3]
local callback_data_key = KEYS[4]
local ready_key = KEYS[5]
local marker_key = KEYS[6]
local provider_result_key = KEYS[7]

local callback_id = ARGV[1]
local order_id = ARGV[2]
local ready_score = ARGV[3]
local idempotency_ttl = tonumber(ARGV[4])
local callback_ttl = tonumber(ARGV[5])
local marker_ttl = tonumber(ARGV[6])
local provider_ttl = tonumber(ARGV[7])

local existing_identities = redis.call(
        'MGET', idempotency_key, order_idempotency_key, provider_trade_idempotency_key)
local existing = existing_identities[1]
        or existing_identities[2]
        or existing_identities[3]
if existing then
    return 'DUPLICATE|' .. existing
end

-- Lua 单线程原子执行保证三个身份键要么一起登记，要么在前面的任一命中时完全不产生副作用。
redis.call('SET', idempotency_key, callback_id, 'PX', idempotency_ttl)
redis.call('SET', order_idempotency_key, callback_id, 'PX', idempotency_ttl)
redis.call('SET', provider_trade_idempotency_key, callback_id, 'PX', idempotency_ttl)

local callback_field_count = tonumber(ARGV[8])
local argument_index = 9
local callback_fields = {}
for index = 1, callback_field_count do
    callback_fields[#callback_fields + 1] = ARGV[argument_index]
    callback_fields[#callback_fields + 1] = ARGV[argument_index + 1]
    argument_index = argument_index + 2
end
if #callback_fields > 0 then
    redis.call('HSET', callback_data_key, unpack(callback_fields))
end
redis.call('PEXPIRE', callback_data_key, callback_ttl)
redis.call('ZADD', ready_key, ready_score, callback_id)
redis.call('SET', marker_key, callback_id, 'PX', marker_ttl)

local provider_field_count = tonumber(ARGV[argument_index])
argument_index = argument_index + 1
redis.call('UNLINK', provider_result_key)
local provider_fields = {}
for index = 1, provider_field_count do
    provider_fields[#provider_fields + 1] = ARGV[argument_index]
    provider_fields[#provider_fields + 1] = ARGV[argument_index + 1]
    argument_index = argument_index + 2
end
if #provider_fields > 0 then
    redis.call('HSET', provider_result_key, unpack(provider_fields))
end
redis.call('PEXPIRE', provider_result_key, provider_ttl)
return 'ENQUEUED|' .. callback_id
