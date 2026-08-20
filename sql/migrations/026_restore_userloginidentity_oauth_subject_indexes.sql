-- GitHub稳定主体只允许绑定一个本地账号；NULL表示尚未绑定且可以重复存在。
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS
    uk_userloginidentity_github_subject
    ON userloginidentity (github_subject)
    WHERE github_subject IS NOT NULL;

-- Google OIDC sub只允许绑定一个本地账号；NULL表示尚未绑定且可以重复存在。
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS
    uk_userloginidentity_google_subject
    ON userloginidentity (google_subject)
    WHERE google_subject IS NOT NULL;
