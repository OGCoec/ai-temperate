local snapshot_key = KEYS[1]
local marker_key = KEYS[2]
local dirty_key = KEYS[3]

local callback_id = ARGV[1]
local provider_trade_no = ARGV[2]
local paid_amount_yuan = ARGV[3]
local paid_at_micros = ARGV[4]
local changed_at_micros = ARGV[5]
local dirty_score_millis = ARGV[6]
local ttl_millis = tonumber(ARGV[7])
local order_id = ARGV[8]

local snapshot = redis.call('HMGET', snapshot_key,
        'status', 'stateVersion', 'providerTradeNo', 'payAmountYuan')
local status = snapshot[1]
local current_version = tonumber(snapshot[2])
if not snapshot[1] and not snapshot[2]
        and not snapshot[3] and not snapshot[4] then
    return 'MISSING||0'
end
if not status or not current_version then
    return 'NOT_ALLOWED||0'
end

local function delete_exact_marker()
    if redis.call('GET', marker_key) == callback_id then
        redis.call('DEL', marker_key)
    end
end

if status == 'CANCELLED' or status == 'CLOSED' then
    delete_exact_marker()
    return 'LATE_TERMINAL|' .. status .. '|' .. current_version
end

local existing_trade_no = snapshot[3]
if existing_trade_no and existing_trade_no ~= '' and existing_trade_no ~= provider_trade_no then
    return 'PROVIDER_TRADE_CONFLICT|' .. status .. '|' .. current_version
end

local expected_amount = snapshot[4]
if not expected_amount or expected_amount ~= paid_amount_yuan then
    return 'AMOUNT_MISMATCH|' .. status .. '|' .. current_version
end

if status == 'PAID' then
    delete_exact_marker()
    redis.call('ZADD', dirty_key, dirty_score_millis, order_id .. '#' .. current_version)
    redis.call('PEXPIRE', snapshot_key, ttl_millis)
    return 'ALREADY_APPLIED|PAID|' .. current_version
end

if status ~= 'PENDING_PAYMENT' and status ~= 'CLOSING' then
    return 'NOT_ALLOWED|' .. status .. '|' .. current_version
end

local next_version = current_version + 1
redis.call('HSET', snapshot_key,
        'status', 'PAID',
        'providerTradeNo', provider_trade_no,
        'paidAt', paid_at_micros,
        'stateVersion', tostring(next_version),
        'updatedAt', changed_at_micros)
redis.call('PEXPIRE', snapshot_key, ttl_millis)
redis.call('ZADD', dirty_key, dirty_score_millis, order_id .. '#' .. next_version)
delete_exact_marker()
return 'APPLIED|PAID|' .. next_version
