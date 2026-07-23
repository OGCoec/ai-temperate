SELECT id, password_version
FROM userloginidentity
WHERE password_version IS NULL
   OR password_version <= 0
ORDER BY id;
