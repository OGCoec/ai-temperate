if redis.call('EXISTS', KEYS[1]) == 1 then return 1 end
redis.call('HSET', KEYS[1],
        'flowId', ARGV[1],
        'bindingHash', ARGV[2],
        'provider', ARGV[3],
        'platform', ARGV[4],
        'codeVerifier', ARGV[5],
        'nonceHash', ARGV[6],
        'redirectUri', ARGV[7],
        'expiresAt', ARGV[8])
redis.call('PEXPIREAT', KEYS[1], ARGV[8])
return 0
