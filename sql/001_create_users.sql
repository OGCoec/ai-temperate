BEGIN;

CREATE TABLE userloginidentity (
    id BIGINT NOT NULL,
    email VARCHAR(254) NOT NULL,
    phone VARCHAR(20),
    password_hash VARCHAR(255) NOT NULL,
    password_version BIGINT NOT NULL DEFAULT 1,
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
COMMENT ON COLUMN userloginidentity.email IS '用户邮箱，唯一性不区分大小写';
COMMENT ON COLUMN userloginidentity.phone IS '规范化后的 E.164 电话号码';
COMMENT ON COLUMN userloginidentity.password_hash IS '使用 Argon2id 或 BCrypt 生成的密码哈希';
COMMENT ON COLUMN userloginidentity.password_version IS
    '密码凭据版本；真实密码创建或修改时递增，单纯哈希算法升级不得递增';
COMMENT ON COLUMN userloginidentity.created_at IS '创建时间';
COMMENT ON COLUMN userloginidentity.updated_at IS '最后更新时间';

COMMIT;
