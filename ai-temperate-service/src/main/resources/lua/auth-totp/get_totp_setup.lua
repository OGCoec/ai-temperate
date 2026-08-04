if redis.call('EXISTS', KEYS[1]) == 0 then
    return {1}
end
local values = redis.call('HMGET', KEYS[1],
    'setupTokenHash', 'deviceHash', 'encryptedSecret', 'action',
    'expectedEnabled', 'expectedEncryptedSecret', 'failedAttempts', 'expiresAt')
if values[1] ~= ARGV[1] or values[2] ~= ARGV[2] then
    return {2}
end
if tonumber(values[8] or '0') <= tonumber(ARGV[3]) then
    redis.call('UNLINK', KEYS[1])
    return {1}
end
return {0, values[3], values[4], values[5], values[6], values[7], values[8]}
