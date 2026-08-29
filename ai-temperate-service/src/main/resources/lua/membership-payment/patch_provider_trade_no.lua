local snapshot_key = KEYS[1]
local expected_owner = ARGV[1]
local provider_trade_no = ARGV[2]

local values = redis.call(
        'HMGET', snapshot_key, 'loginIdentityId', 'status', 'providerTradeNo')
if not values[1] then
    return 'MISSING'
end
if values[1] ~= expected_owner or values[2] ~= 'PENDING_PAYMENT' then
    return 'CONFLICT'
end
if not values[3] or values[3] == '' then
    redis.call('HSET', snapshot_key, 'providerTradeNo', provider_trade_no)
    return 'APPLIED'
end
if values[3] == provider_trade_no then
    return 'UNCHANGED'
end
return 'CONFLICT'
