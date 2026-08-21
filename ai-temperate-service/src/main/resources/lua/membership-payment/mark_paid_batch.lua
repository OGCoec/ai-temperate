local dirty_key = KEYS[1]
local count = tonumber(ARGV[1])
local ttl_millis = tonumber(ARGV[2])
local argument_index = 3
local results = {}

for index = 1, count do
    local snapshot_key = KEYS[index * 2]
    local marker_key = KEYS[index * 2 + 1]
    local callback_id = ARGV[argument_index]
    local order_id = ARGV[argument_index + 1]
    local provider_trade_no = ARGV[argument_index + 2]
    local paid_amount_yuan = ARGV[argument_index + 3]
    local paid_at = ARGV[argument_index + 4]
    local changed_at = ARGV[argument_index + 5]
    argument_index = argument_index + 6

    local function result(outcome, status, version)
        table.insert(results,
                callback_id .. '|' .. outcome .. '|' .. (status or '') .. '|' .. tostring(version or 0))
    end
    local function delete_exact_marker()
        if redis.call('GET', marker_key) == callback_id then
            redis.call('UNLINK', marker_key)
        end
    end

    if redis.call('EXISTS', snapshot_key) == 0 then
        result('MISSING', '', 0)
    else
        local status = redis.call('HGET', snapshot_key, 'status')
        local current_version = tonumber(redis.call('HGET', snapshot_key, 'stateVersion'))
        if not status or not current_version then
            result('NOT_ALLOWED', '', 0)
        elseif status == 'CANCELLED' or status == 'CLOSED' then
            delete_exact_marker()
            result('LATE_TERMINAL', status, current_version)
        else
            local existing_trade_no = redis.call('HGET', snapshot_key, 'providerTradeNo')
            local expected_amount = redis.call('HGET', snapshot_key, 'payAmountYuan')
            if existing_trade_no and existing_trade_no ~= ''
                    and existing_trade_no ~= provider_trade_no then
                result('PROVIDER_TRADE_CONFLICT', status, current_version)
            elseif not expected_amount or expected_amount ~= paid_amount_yuan then
                result('AMOUNT_MISMATCH', status, current_version)
            elseif status == 'PAID' then
                delete_exact_marker()
                redis.call('ZADD', dirty_key, changed_at, order_id .. '#' .. current_version)
                redis.call('PEXPIRE', snapshot_key, ttl_millis)
                result('ALREADY_APPLIED', status, current_version)
            elseif status == 'PENDING_PAYMENT' or status == 'CLOSING' then
                local next_version = current_version + 1
                redis.call('HSET', snapshot_key,
                        'status', 'PAID',
                        'providerTradeNo', provider_trade_no,
                        'paidAt', paid_at,
                        'stateVersion', tostring(next_version),
                        'updatedAt', changed_at)
                redis.call('PEXPIRE', snapshot_key, ttl_millis)
                redis.call('ZADD', dirty_key, changed_at, order_id .. '#' .. next_version)
                delete_exact_marker()
                result('APPLIED', 'PAID', next_version)
            else
                result('NOT_ALLOWED', status, current_version)
            end
        end
    end
end
return results
