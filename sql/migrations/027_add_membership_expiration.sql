ALTER TABLE user_membership_quota
    ADD COLUMN IF NOT EXISTS membership_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN user_membership_quota.membership_expires_at
    IS '当前付费会员订阅到期时间；FREE 允许为空，付费等级为空时由惰性过期逻辑降级为 FREE';
