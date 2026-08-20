BEGIN;

CREATE TABLE user_membership_quota (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    login_identity_id BIGINT NOT NULL,
    membership_tier SMALLINT NOT NULL DEFAULT 0,
    quota_balance_minor BIGINT NOT NULL DEFAULT 5000,
    quota_period_started_at TIMESTAMPTZ,
    quota_period_ends_at TIMESTAMPTZ,
    membership_expires_at TIMESTAMPTZ,

    CONSTRAINT pk_user_membership_quota PRIMARY KEY (id),
    CONSTRAINT uk_user_membership_quota_login_identity
        UNIQUE (login_identity_id),
    CONSTRAINT chk_user_membership_quota_tier
        CHECK (membership_tier BETWEEN 0 AND 6),
    CONSTRAINT chk_user_membership_quota_balance_non_negative
        CHECK (quota_balance_minor >= 0)
);

COMMENT ON TABLE user_membership_quota IS '用户当前会员等级与可用额度表，与 userloginidentity 通过 login_identity_id 进行一对一逻辑关联';
COMMENT ON COLUMN user_membership_quota.id IS 'BIGINT 自增主键，对外使用 Base64URL 编码';
COMMENT ON COLUMN user_membership_quota.login_identity_id IS '逻辑关联 userloginidentity.id，不建立物理外键';
COMMENT ON COLUMN user_membership_quota.membership_tier IS '会员等级：0=FREE，1=GO，2=EDU，3=TEAM，4=PLUS，5=PRO，6=MAX';
COMMENT ON COLUMN user_membership_quota.quota_balance_minor IS '可用额度的最小单位整数值；固定缩放比例为 100，数据库值 5000 表示实际额度 50.00';
COMMENT ON COLUMN user_membership_quota.quota_period_started_at IS '当前额度周期实际开始时间；新用户尚未消费额度时为空';
COMMENT ON COLUMN user_membership_quota.quota_period_ends_at IS '当前额度周期结束时间；新用户注册时由业务服务写入当前 UTC 时间，使首次模型调用进入新周期';
COMMENT ON COLUMN user_membership_quota.membership_expires_at IS '当前付费会员订阅到期时间；FREE 允许为空，付费等级为空时由惰性过期逻辑降级为 FREE';

COMMIT;
