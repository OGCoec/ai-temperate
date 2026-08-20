-- 该检查只返回重复组计数和索引有效性，不输出任何第三方Subject值。
WITH github_duplicate_groups AS (
    SELECT 1
    FROM userloginidentity
    WHERE github_subject IS NOT NULL
    GROUP BY github_subject
    HAVING COUNT(*) > 1
),
google_duplicate_groups AS (
    SELECT 1
    FROM userloginidentity
    WHERE google_subject IS NOT NULL
    GROUP BY google_subject
    HAVING COUNT(*) > 1
),
oauth_subject_indexes AS (
    SELECT
        index_class.relname AS index_name,
        index_metadata.indisunique AS is_unique,
        index_metadata.indisvalid AS is_valid,
        LOWER(pg_get_indexdef(index_class.oid)) AS index_definition
    FROM pg_class table_class
    INNER JOIN pg_namespace table_namespace
        ON table_namespace.oid = table_class.relnamespace
    INNER JOIN pg_index index_metadata
        ON index_metadata.indrelid = table_class.oid
    INNER JOIN pg_class index_class
        ON index_class.oid = index_metadata.indexrelid
    WHERE table_namespace.nspname = 'public'
      AND table_class.relname = 'userloginidentity'
      AND index_class.relname IN (
          'uk_userloginidentity_github_subject',
          'uk_userloginidentity_google_subject'
      )
)
SELECT
    (SELECT COUNT(*) FROM github_duplicate_groups)
        AS github_duplicate_group_count,
    (SELECT COUNT(*) FROM google_duplicate_groups)
        AS google_duplicate_group_count,
    EXISTS (
        SELECT 1
        FROM oauth_subject_indexes
        WHERE index_name = 'uk_userloginidentity_github_subject'
          AND is_unique
          AND is_valid
          AND index_definition LIKE '%using btree (github_subject)%'
          AND index_definition LIKE '%where (github_subject is not null)%'
    ) AS github_index_valid,
    EXISTS (
        SELECT 1
        FROM oauth_subject_indexes
        WHERE index_name = 'uk_userloginidentity_google_subject'
          AND is_unique
          AND is_valid
          AND index_definition LIKE '%using btree (google_subject)%'
          AND index_definition LIKE '%where (google_subject is not null)%'
    ) AS google_index_valid;
