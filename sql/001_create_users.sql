BEGIN;

CREATE TABLE userloginidentity (
    id BIGINT NOT NULL,
    registration_source SMALLINT NOT NULL DEFAULT 0,
    github_subject VARCHAR(255),
    google_subject VARCHAR(255),
    email VARCHAR(254) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone VARCHAR(20),
    password_hash VARCHAR(255),
    password_version BIGINT NOT NULL DEFAULT 1,
    totp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    totp_secret_encrypted VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_userloginidentity PRIMARY KEY (id),
    CONSTRAINT chk_userloginidentity_password_version_positive
        CHECK (password_version > 0)
);

-- 邮箱登录不区分大小写，同时保证邮箱唯一。
-- PostgreSQL 的唯一索引默认使用 B-tree。
CREATE UNIQUE INDEX uk_userloginidentity_email_lower
    ON userloginidentity (LOWER(email));

-- GitHub 稳定主体 ID 只允许绑定一个用户；未绑定时允许保持 NULL。
CREATE UNIQUE INDEX uk_userloginidentity_github_subject
    ON userloginidentity (github_subject)
    WHERE github_subject IS NOT NULL;

-- Google OpenID Connect sub 只允许绑定一个用户；未绑定时允许保持 NULL。
CREATE UNIQUE INDEX uk_userloginidentity_google_subject
    ON userloginidentity (google_subject)
    WHERE google_subject IS NOT NULL;

-- 电话号码应在写入前统一为 E.164 格式，例如：+8613812345678。
-- 仅索引非 NULL 数据，允许多个用户暂时不填写电话号码。
CREATE UNIQUE INDEX uk_userloginidentity_phone
    ON userloginidentity (phone)
    WHERE phone IS NOT NULL;

CREATE OR REPLACE FUNCTION set_userloginidentity_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_userloginidentity_set_updated_at
    BEFORE UPDATE ON userloginidentity
    FOR EACH ROW
    EXECUTE FUNCTION set_userloginidentity_updated_at();

COMMENT ON TABLE userloginidentity IS '用户登录身份表';
COMMENT ON COLUMN userloginidentity.id IS '由应用程序生成的 BIGINT 用户 ID，例如雪花算法 ID';
COMMENT ON COLUMN userloginidentity.registration_source IS
    '账号首次注册来源；0 表示邮箱注册，1 表示 GitHub，2 表示 Google';
COMMENT ON COLUMN userloginidentity.github_subject IS
    'GitHub 提供的稳定用户唯一标识；未绑定 GitHub 登录时保持为空';
COMMENT ON COLUMN userloginidentity.google_subject IS
    'Google OpenID Connect 提供的稳定 sub；未绑定 Google 登录时保持为空';
COMMENT ON COLUMN userloginidentity.email IS '用户邮箱，唯一性不区分大小写';
COMMENT ON COLUMN userloginidentity.email_verified IS
    '邮箱是否已由本站验证流程或受信任的第三方平台确认归属';
COMMENT ON COLUMN userloginidentity.phone IS '规范化后的 E.164 电话号码';
COMMENT ON COLUMN userloginidentity.password_hash IS
    '使用 Spring Security PasswordEncoder 生成的密码哈希；未设置本地密码的第三方账号保持为空';
COMMENT ON COLUMN userloginidentity.password_version IS
    '密码凭据版本；真实密码创建或修改时递增，单纯哈希算法升级不得递增';
COMMENT ON COLUMN userloginidentity.totp_enabled IS
    '是否启用基于时间的一次性密码（TOTP）二次认证，默认关闭';
COMMENT ON COLUMN userloginidentity.totp_secret_encrypted IS
    'TOTP 三十二字节共享密钥的加密存储值；Base32 仅用于配置展示，未启用时保持为空';
COMMENT ON COLUMN userloginidentity.created_at IS '创建时间';
COMMENT ON COLUMN userloginidentity.updated_at IS '最后更新时间';

COMMIT;
