BEGIN;

CREATE TABLE user_api_key (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    login_identity_id BIGINT NOT NULL,
    key_digest BYTEA NOT NULL,
    key_hint VARCHAR(4) NOT NULL,
    status SMALLINT NOT NULL DEFAULT 1,
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT pk_user_api_key
        PRIMARY KEY (id),
    CONSTRAINT chk_user_api_key_digest_length
        CHECK (OCTET_LENGTH(key_digest) = 32),
    CONSTRAINT chk_user_api_key_hint
        CHECK (key_hint ~ '^[A-Za-z0-9_-]{4}$'),
    CONSTRAINT chk_user_api_key_status
        CHECK (status IN (0, 1, 2)),
    CONSTRAINT chk_user_api_key_row_version
        CHECK (row_version >= 0),
    CONSTRAINT chk_user_api_key_deleted_state
        CHECK (
            (status IN (0, 1) AND deleted_at IS NULL)
            OR (status = 2 AND deleted_at IS NOT NULL)
        )
);

-- 认证请求只执行摘要等值查询；唯一索引同时禁止已软删除凭证被重新使用。
CREATE UNIQUE INDEX uk_user_api_key_digest
    ON user_api_key (key_digest);

-- 用户列表只展示启用和禁用记录；软删除状态不进入该索引。
CREATE INDEX idx_user_api_key_owner_created
    ON user_api_key (
        login_identity_id,
        created_at DESC,
        id DESC
    )
    WHERE status IN (0, 1);

CREATE OR REPLACE FUNCTION set_user_api_key_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_api_key_set_updated_at
    BEFORE UPDATE ON user_api_key
    FOR EACH ROW
    EXECUTE FUNCTION set_user_api_key_updated_at();

COMMENT ON TABLE user_api_key IS
    '用户创建的外部 API 调用凭证；完整 API Key 只在创建响应中返回一次，本表不保存可恢复的完整凭证';
COMMENT ON COLUMN user_api_key.id IS
    'PostgreSQL 自动递增的 BIGINT API Key 记录主键，对外使用 Base64URL 编码';
COMMENT ON COLUMN user_api_key.login_identity_id IS
    '持有该 API Key 的登录身份 ID，逻辑关联 userloginidentity.id，不建立物理外键';
COMMENT ON COLUMN user_api_key.key_digest IS
    '完整 API Key 经用途隔离 HMAC-SHA256 计算得到的固定 32 字节摘要，用于认证等值查询，禁止返回客户端';
COMMENT ON COLUMN user_api_key.key_hint IS
    '完整 API Key 的末尾四个 Base64URL 字符，只用于拼接不可还原的脱敏展示值';
COMMENT ON COLUMN user_api_key.status IS
    '凭证状态：0=DISABLED，1=ENABLED，2=DELETED；DELETED 为不可恢复的软删除状态';
COMMENT ON COLUMN user_api_key.expires_at IS
    '凭证过期时间；NULL 表示永不过期，不使用负数或特殊时间值表达永久有效';
COMMENT ON COLUMN user_api_key.last_used_at IS
    '最近一次成功完成预扣事务的 API 调用时间；该字段更新不改变面向管理接口的行版本';
COMMENT ON COLUMN user_api_key.row_version IS
    '乐观锁版本号，状态或过期时间修改成功时由应用程序递增';
COMMENT ON COLUMN user_api_key.created_at IS
    'API Key 创建时间';
COMMENT ON COLUMN user_api_key.updated_at IS
    'API Key 状态、过期时间或软删除信息的最后修改时间';
COMMENT ON COLUMN user_api_key.deleted_at IS
    '软删除时间；仅当 status=2 时非空';

COMMENT ON INDEX uk_user_api_key_digest IS
    '支持通过 HMAC 摘要唯一定位 API Key 认证记录，并阻止历史摘要重新写入';
COMMENT ON INDEX idx_user_api_key_owner_created IS
    '支持按用户、创建时间和主键倒序进行稳定游标分页，不包含软删除记录';

COMMIT;
