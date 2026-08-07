-- 列出异步 Generation 两张表；缺失时脚本应返回少于两行并由演练脚本判定失败。
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN (
      'ai_conversation_generation',
      'ai_conversation_generation_payload')
ORDER BY table_name;

-- 核对两张表不存在项目禁止的物理外键。
SELECT COUNT(*) AS forbidden_foreign_key_count
FROM pg_constraint constraint_definition
JOIN pg_class table_definition
  ON table_definition.oid = constraint_definition.conrelid
JOIN pg_namespace namespace_definition
  ON namespace_definition.oid = table_definition.relnamespace
WHERE namespace_definition.nspname = 'public'
  AND table_definition.relname IN (
      'ai_conversation_generation',
      'ai_conversation_generation_payload')
  AND constraint_definition.contype = 'f';

-- 列出实际索引定义，供预发布演练对照迁移中的状态查询、Owner、用户活动任务和唯一键设计。
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN (
      'ai_conversation_generation',
      'ai_conversation_generation_payload')
ORDER BY tablename, indexname;

-- 表和字段中文 COMMENT 必须存在；返回行表示存在缺失注释。
SELECT table_definition.relname AS table_name,
       attribute_definition.attname AS column_name
FROM pg_class table_definition
JOIN pg_namespace namespace_definition
  ON namespace_definition.oid = table_definition.relnamespace
JOIN pg_attribute attribute_definition
  ON attribute_definition.attrelid = table_definition.oid
WHERE namespace_definition.nspname = 'public'
  AND table_definition.relname IN (
      'ai_conversation_generation',
      'ai_conversation_generation_payload')
  AND table_definition.relkind = 'r'
  AND attribute_definition.attnum > 0
  AND NOT attribute_definition.attisdropped
  AND col_description(
      table_definition.oid,
      attribute_definition.attnum) IS NULL
ORDER BY table_definition.relname, attribute_definition.attnum;

-- 将关键目录检查转换为发布门禁；任一缺失项都必须使 psql 以非零状态退出。
DO $verification$
DECLARE
    table_count INTEGER;
    foreign_key_count INTEGER;
    missing_comment_count INTEGER;
    missing_index_count INTEGER;
    missing_metering_column_count INTEGER;
    missing_metering_constraint_count INTEGER;
BEGIN
    SELECT COUNT(*)
      INTO table_count
      FROM information_schema.tables
     WHERE table_schema = 'public'
       AND table_name IN (
           'ai_conversation_generation',
           'ai_conversation_generation_payload');
    IF table_count <> 2 THEN
        RAISE EXCEPTION 'AI Generation schema requires exactly two tables, found %', table_count;
    END IF;

    SELECT COUNT(*)
      INTO foreign_key_count
      FROM pg_constraint constraint_definition
      JOIN pg_class table_definition
        ON table_definition.oid = constraint_definition.conrelid
      JOIN pg_namespace namespace_definition
        ON namespace_definition.oid = table_definition.relnamespace
     WHERE namespace_definition.nspname = 'public'
       AND table_definition.relname IN (
           'ai_conversation_generation',
           'ai_conversation_generation_payload')
       AND constraint_definition.contype = 'f';
    IF foreign_key_count <> 0 THEN
        RAISE EXCEPTION 'AI Generation schema contains % forbidden foreign keys', foreign_key_count;
    END IF;

    SELECT COUNT(*)
      INTO missing_comment_count
      FROM pg_class table_definition
      JOIN pg_namespace namespace_definition
        ON namespace_definition.oid = table_definition.relnamespace
      JOIN pg_attribute attribute_definition
        ON attribute_definition.attrelid = table_definition.oid
     WHERE namespace_definition.nspname = 'public'
       AND table_definition.relname IN (
           'ai_conversation_generation',
           'ai_conversation_generation_payload')
       AND table_definition.relkind = 'r'
       AND attribute_definition.attnum > 0
       AND NOT attribute_definition.attisdropped
       AND col_description(
           table_definition.oid,
           attribute_definition.attnum) IS NULL;
    IF missing_comment_count <> 0 THEN
        RAISE EXCEPTION 'AI Generation schema has % columns without comments', missing_comment_count;
    END IF;

    SELECT COUNT(*)
      INTO missing_index_count
      FROM (VALUES
          ('idx_ai_conversation_generation_recovery'),
          ('idx_ai_conversation_generation_owner'),
          ('idx_ai_conversation_generation_user_active'),
          ('idx_ai_conversation_generation_detached_due'),
          ('idx_ai_conversation_generation_conversation'),
          ('uq_ai_conversation_generation_conversation_active'),
          ('idx_ai_conversation_generation_model'),
          ('idx_ai_conversation_generation_payload_message')
      ) AS required(index_name)
      LEFT JOIN pg_indexes actual
        ON actual.schemaname = 'public'
       AND actual.indexname = required.index_name
     WHERE actual.indexname IS NULL;
    IF missing_index_count <> 0 THEN
        RAISE EXCEPTION 'AI Generation schema is missing % required indexes', missing_index_count;
    END IF;

    -- 成本计量字段是异步图片恢复与对账的权威证据，缺少任一列都不得继续发布。
    SELECT COUNT(*)
      INTO missing_metering_column_count
      FROM (VALUES
          ('ai_conversation_generation_payload', 'metering_basis'),
          ('ai_conversation_generation_payload', 'provider_cost_ticks'),
          ('ai_conversation_generation_payload', 'metering_evidence')
      ) AS required(table_name, column_name)
      LEFT JOIN information_schema.columns actual
        ON actual.table_schema = 'public'
       AND actual.table_name = required.table_name
       AND actual.column_name = required.column_name
     WHERE actual.column_name IS NULL;
    IF missing_metering_column_count <> 0 THEN
        RAISE EXCEPTION 'AI Generation schema is missing % metering columns', missing_metering_column_count;
    END IF;

    SELECT COUNT(*)
      INTO missing_metering_constraint_count
      FROM (VALUES
          ('chk_ai_conversation_generation_payload_metering_basis'),
          ('chk_ai_conversation_generation_payload_provider_cost'),
          ('chk_ai_conversation_generation_payload_metering_fields')
      ) AS required(constraint_name)
      LEFT JOIN pg_constraint actual
        ON actual.conname = required.constraint_name
     WHERE actual.conname IS NULL;
    IF missing_metering_constraint_count <> 0 THEN
        RAISE EXCEPTION 'AI Generation schema is missing % metering constraints', missing_metering_constraint_count;
    END IF;
END
$verification$;
