local snapshot_key = KEYS[1]
local expected_owner = ARGV[1]
local provider_trade_no = ARGV[2]

local values = redis.call(
        'HMGET', snapshot_key, 'loginIdentityId', 'status', 'providerTradeNo')
if not values[1] then
    return 'MISSING'
end
if values[1] ~= expected_owner then
    return 'CONFLICT'
end
-- 真实第三方流水是单调增加的支付事实：关单、终态或晚到回调只允许空值补写或相同值重放，绝不能覆盖既有不同流水。
local valid_status = values[2] == 'PENDING_PAYMENT'
        or values[2] == 'CLOSING'
        or values[2] == 'PAID'
        or values[2] == 'CANCELLED'
        or values[2] == 'CLOSED'
if not valid_status then
    return 'CONFLICT'
end
local valid_provider_trade = string.match(provider_trade_no, '^BAR:TRADE:.+$')
        or string.match(provider_trade_no, '^LIUHAO:TRADE:.+$')
if not valid_provider_trade then
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
