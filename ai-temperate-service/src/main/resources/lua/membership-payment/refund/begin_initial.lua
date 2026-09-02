local state = redis.call('HGET', KEYS[1], 'state')
if not state then
    redis.call('HSET', KEYS[1], 'state', 'READY', 'attemptNo', '1')
    redis.call('HSET', KEYS[1], 'state', 'ATTEMPTING')
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
    return 'ATTEMPT_PROVIDER|1||||'
end

local attempt = redis.call('HGET', KEYS[1], 'attemptNo') or '0'
local messageId = redis.call('HGET', KEYS[1], 'messageId') or ''
local nextAttempt = redis.call('HGET', KEYS[1], 'nextAttemptNo') or ''
local terminalOutcome = redis.call('HGET', KEYS[1], 'terminalOutcome') or ''
local safeReason = redis.call('HGET', KEYS[1], 'safeReason') or ''
redis.call('PEXPIRE', KEYS[1], ARGV[1])

if state == 'RETRY_PUBLISH_PENDING' then
    return 'PUBLISH_RETRY|' .. attempt .. '|' .. messageId .. '|' .. nextAttempt .. '||' .. safeReason
end
if state == 'TERMINAL_PUBLISH_PENDING' then
    return 'PUBLISH_TERMINAL|' .. attempt .. '|' .. messageId .. '||' .. terminalOutcome .. '|' .. safeReason
end
if state == 'ATTEMPTING' then
    return 'ATTEMPT_OUTCOME_UNKNOWN|' .. attempt .. '||||'
end
if state == 'RETRY_WAIT' or state == 'SUCCEEDED' or state == 'TERMINAL' then
    return 'COMPLETE_COORDINATED|' .. attempt .. '|' .. messageId .. '|' .. nextAttempt .. '|' .. terminalOutcome .. '|' .. safeReason
end
return 'MESSAGE_NOT_READY|' .. attempt .. '|' .. messageId .. '|' .. nextAttempt .. '|' .. terminalOutcome .. '|' .. safeReason
