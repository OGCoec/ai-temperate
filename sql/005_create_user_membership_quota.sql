BEGIN;

CREATE TABLE user_membership_quota (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    login_identity_id BIGINT NOT NULL,
    membership_tier SMALLINT NOT NULL DEFAULT 0,
    quota_balance_minor BIGINT NOT NULL DEFAULT 5000,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_user_membership_quota PRIMARY KEY (id),
    CONSTRAINT uk_user_membership_quota_login_identity
        UNIQUE (login_identity_id),
    CONSTRAINT chk_user_membership_quota_tier
        CHECK (membership_tier BETWEEN 0 AND 6),
    CONSTRAINT chk_user_membership_quota_balance_non_negative
        CHECK (quota_balance_minor >= 0)
);

CREATE OR REPLACE FUNCTION set_user_membership_quota_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_membership_quota_set_updated_at
    BEFORE UPDATE ON user_membership_quota
    FOR EACH ROW
    EXECUTE FUNCTION set_user_membership_quota_updated_at();

COMMENT ON TABLE user_membership_quota IS '用户当前会员等级与可用额度表，与 userloginidentity 通过 login_identity_id 进行一对一逻辑关联';
COMMENT ON COLUMN user_membership_quota.id IS 'BIGINT 自增主键，对外使用 Base64URL 编码';
COMMENT ON COLUMN user_membership_quota.login_identity_id IS '逻辑关联 userloginidentity.id，不建立物理外键';
COMMENT ON COLUMN user_membership_quota.membership_tier IS '会员等级：0=FREE，1=GO，2=EDU，3=TEAM，4=PLUS，5=PRO，6=MAX';
COMMENT ON COLUMN user_membership_quota.quota_balance_minor IS '可用额度的最小单位整数值；固定缩放比例为 100，数据库值 5000 表示实际额度 50.00';
COMMENT ON COLUMN user_membership_quota.created_at IS '会员额度记录创建时间';
COMMENT ON COLUMN user_membership_quota.updated_at IS '会员额度记录最后更新时间';

COMMIT;
