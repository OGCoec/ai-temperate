redis.call('HSET', KEYS[1],
    'schemaVersion', '1',
    'setupTokenHash', ARGV[1],
    'deviceHash', ARGV[2],
    'encryptedSecret', ARGV[3],
    'action', ARGV[4],
    'expectedEnabled', ARGV[5],
    'expectedEncryptedSecret', ARGV[6],
    'failedAttempts', '0',
    'createdAt', ARGV[7],
    'expiresAt', ARGV[8])
redis.call('PEXPIRE', KEYS[1], ARGV[9])
return 0
