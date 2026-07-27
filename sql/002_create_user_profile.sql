BEGIN;

CREATE TABLE user_profile (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    login_identity_id BIGINT NOT NULL,
    display_name VARCHAR(64),
    avatar_url VARCHAR(1024),
    gender SMALLINT NOT NULL DEFAULT 0,
    birthday DATE,
    bio VARCHAR(255),
    account_status SMALLINT NOT NULL DEFAULT 0,
    status_reason VARCHAR(255),
    frozen_until TIMESTAMPTZ,
    status_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_user_profile PRIMARY KEY (id),
    CONSTRAINT uk_user_profile_login_identity UNIQUE (login_identity_id),
    CONSTRAINT chk_user_profile_gender
        CHECK (gender BETWEEN 0 AND 3),
    CONSTRAINT chk_user_profile_account_status
        CHECK (account_status BETWEEN 0 AND 2)
);

-- 用户列表按账户状态筛选后，以展示名称和主键进行稳定排序；低基数状态不单独建立普通索引。
CREATE INDEX idx_user_profile_account_status_display_name_id
    ON user_profile (
        account_status ASC,
        display_name ASC NULLS LAST,
        id ASC
    );

CREATE OR REPLACE FUNCTION set_user_profile_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.account_status IS DISTINCT FROM OLD.account_status THEN
        NEW.status_changed_at = CURRENT_TIMESTAMP;
    END IF;

    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_profile_set_updated_at
    BEFORE UPDATE ON user_profile
    FOR EACH ROW
    EXECUTE FUNCTION set_user_profile_updated_at();

COMMENT ON TABLE user_profile IS '用户详细资料表，与 userloginidentity 通过 login_identity_id 进行一对一逻辑关联';
COMMENT ON COLUMN user_profile.id IS 'BIGINT 自增主键，对外使用 Base64URL 编码';
COMMENT ON COLUMN user_profile.login_identity_id IS '逻辑关联 userloginidentity.id，不建立物理外键';
COMMENT ON COLUMN user_profile.display_name IS '用户展示名称';
COMMENT ON COLUMN user_profile.avatar_url IS '用户当前头像的公开访问 URL；旧头像和临时对象残留由 OSS 生命周期策略处理';
COMMENT ON COLUMN user_profile.gender IS '性别：0=UNDISCLOSED，1=MALE，2=FEMALE，3=OTHER';
COMMENT ON COLUMN user_profile.birthday IS '用户生日；年龄由 birthday 动态计算，不直接存储';
COMMENT ON COLUMN user_profile.bio IS '用户个人简介';
COMMENT ON COLUMN user_profile.account_status IS '账户状态：0=ACTIVE，1=FROZEN，2=DEACTIVATED';
COMMENT ON COLUMN user_profile.status_reason IS '账户状态变更原因';
COMMENT ON COLUMN user_profile.frozen_until IS '账户冻结截止时间；NULL 表示未设置自动解冻时间';
COMMENT ON COLUMN user_profile.status_changed_at IS '账户状态最后变更时间';
COMMENT ON COLUMN user_profile.created_at IS '创建时间';
COMMENT ON COLUMN user_profile.updated_at IS '最后更新时间';

COMMIT;
