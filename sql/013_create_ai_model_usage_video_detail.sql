BEGIN;

CREATE TABLE ai_model_usage_video_detail (
    usage_id BYTEA NOT NULL,
    video_mode VARCHAR(32) NOT NULL,
    video_resolution VARCHAR(16) NOT NULL,
    requested_duration_seconds INTEGER NOT NULL,
    input_image_count INTEGER NOT NULL,
    input_video_duration_millis BIGINT NOT NULL,
    output_cost_ticks_per_second BIGINT NOT NULL,
    image_input_cost_ticks_each BIGINT NOT NULL,
    video_input_cost_ticks_per_second BIGINT NOT NULL,
    estimated_provider_cost_ticks BIGINT NOT NULL,

    CONSTRAINT pk_ai_model_usage_video_detail
        PRIMARY KEY (usage_id),

    CONSTRAINT chk_ai_model_usage_video_detail_usage_id
        CHECK (OCTET_LENGTH(usage_id) = 16),

    CONSTRAINT chk_ai_model_usage_video_detail_mode
        CHECK (video_mode IN (
            'TEXT_TO_VIDEO',
            'IMAGE_TO_VIDEO',
            'REFERENCE_TO_VIDEO',
            'VIDEO_EDIT',
            'VIDEO_EXTEND'
        )),

    CONSTRAINT chk_ai_model_usage_video_detail_resolution
        CHECK (video_resolution IN (
            'P480',
            'P720',
            'P1080'
        )),

    CONSTRAINT chk_ai_model_usage_video_detail_numbers
        CHECK (
            requested_duration_seconds >= 0
            AND input_image_count BETWEEN 0 AND 7
            AND input_video_duration_millis >= 0
            AND output_cost_ticks_per_second > 0
            AND image_input_cost_ticks_each >= 0
            AND video_input_cost_ticks_per_second >= 0
            AND estimated_provider_cost_ticks > 0
        ),

    CONSTRAINT chk_ai_model_usage_video_detail_mode_shape
        CHECK (
            (
                video_mode = 'TEXT_TO_VIDEO'
                AND requested_duration_seconds BETWEEN 1 AND 15
                AND input_image_count = 0
                AND input_video_duration_millis = 0
            )
            OR (
                video_mode = 'IMAGE_TO_VIDEO'
                AND requested_duration_seconds BETWEEN 1 AND 15
                AND input_image_count = 1
                AND input_video_duration_millis = 0
            )
            OR (
                video_mode = 'REFERENCE_TO_VIDEO'
                AND requested_duration_seconds BETWEEN 1 AND 15
                AND input_image_count BETWEEN 1 AND 7
                AND input_video_duration_millis = 0
                AND video_resolution IN ('P480', 'P720')
            )
            OR (
                video_mode = 'VIDEO_EDIT'
                AND requested_duration_seconds = 0
                AND input_image_count = 0
                AND input_video_duration_millis > 0
                AND video_resolution IN ('P480', 'P720')
            )
            OR (
                video_mode = 'VIDEO_EXTEND'
                AND requested_duration_seconds BETWEEN 2 AND 10
                AND input_image_count = 0
                AND input_video_duration_millis > 0
                AND video_resolution IN ('P480', 'P720')
            )
        )
);

ALTER TABLE ai_model_usage_detail
    DROP CONSTRAINT chk_ai_model_usage_detail_metering_fields,
    ADD CONSTRAINT chk_ai_model_usage_detail_metering_fields
        CHECK (
            (
                metering_basis = 0
                AND estimated_prompt_tokens IS NOT NULL
                AND max_output_tokens IS NOT NULL
                AND input_ratio_snapshot IS NOT NULL
                AND cached_input_ratio_snapshot IS NOT NULL
                AND output_ratio_snapshot IS NOT NULL
                AND requested_output_count IS NULL
            )
            OR (
                metering_basis = 1
                AND estimated_prompt_tokens IS NULL
                AND max_output_tokens IS NULL
                AND input_ratio_snapshot IS NULL
                AND cached_input_ratio_snapshot IS NULL
                AND output_ratio_snapshot IS NULL
                AND (
                    requested_output_count IS NULL
                    OR requested_output_count BETWEEN 1 AND 10
                )
            )
        );

COMMENT ON TABLE ai_model_usage_video_detail IS
    '模型用量的视频计费一对一扩展详情，冻结视频模式、媒体规模、官方价格和预计供应商成本；通过usage_id与ai_model_usage_detail进行逻辑关联，不建立物理外键';

COMMENT ON COLUMN ai_model_usage_video_detail.usage_id IS
    '逻辑关联ai_model_usage_detail.usage_id的16字节Hybrid ID；同时作为主键，保证一条模型用量最多存在一条视频详情';
COMMENT ON COLUMN ai_model_usage_video_detail.video_mode IS
    '视频操作模式：文本生成视频、图片生成视频、参考图生成视频、视频编辑或视频延长';
COMMENT ON COLUMN ai_model_usage_video_detail.video_resolution IS
    '视频预扣使用的清晰度档位；普通生成支持P480、P720和P1080，参考图、编辑及延长最高为P720';
COMMENT ON COLUMN ai_model_usage_video_detail.requested_duration_seconds IS
    '用户请求的生成秒数；普通生成记录输出时长，视频延长记录新增时长，视频编辑固定为零';
COMMENT ON COLUMN ai_model_usage_video_detail.input_image_count IS
    '参与本次视频请求计费的实际输入图片数量；文本和视频输入模式为零';
COMMENT ON COLUMN ai_model_usage_video_detail.input_video_duration_millis IS
    '由可信FC媒体探测获得的输入视频毫秒时长；普通生成模式为零，编辑和延长用于输入及输出成本估算';
COMMENT ON COLUMN ai_model_usage_video_detail.output_cost_ticks_per_second IS
    '预扣时冻结的官方视频输出每秒美元成本ticks，避免后续价格调整改变历史计费证据';
COMMENT ON COLUMN ai_model_usage_video_detail.image_input_cost_ticks_each IS
    '预扣时冻结的官方图片输入每张美元成本ticks；不使用图片输入的模式可以保存零';
COMMENT ON COLUMN ai_model_usage_video_detail.video_input_cost_ticks_per_second IS
    '预扣时冻结的官方视频输入每秒美元成本ticks；不使用视频输入的模式保存零';
COMMENT ON COLUMN ai_model_usage_video_detail.estimated_provider_cost_ticks IS
    '根据输出时长、输入图片数量、输入视频时长和冻结价格计算的预计供应商总成本ticks，用作额度预扣依据';

COMMENT ON CONSTRAINT pk_ai_model_usage_video_detail
    ON ai_model_usage_video_detail IS
    '使用usage_id保证模型用量与视频详情保持一对零或一关系，并为精确查询和JOIN提供唯一索引';
COMMENT ON CONSTRAINT chk_ai_model_usage_video_detail_usage_id
    ON ai_model_usage_video_detail IS
    '保证usage_id符合项目Hybrid ID固定16字节存储格式';
COMMENT ON CONSTRAINT chk_ai_model_usage_video_detail_mode
    ON ai_model_usage_video_detail IS
    '限制视频模式只能使用当前支持的五种稳定业务枚举';
COMMENT ON CONSTRAINT chk_ai_model_usage_video_detail_resolution
    ON ai_model_usage_video_detail IS
    '限制视频计费清晰度只能使用480p、720p或1080p三个档位';
COMMENT ON CONSTRAINT chk_ai_model_usage_video_detail_numbers
    ON ai_model_usage_video_detail IS
    '禁止保存负时长、负媒体数量、负价格或零预计成本等无效计费快照';
COMMENT ON CONSTRAINT chk_ai_model_usage_video_detail_mode_shape
    ON ai_model_usage_video_detail IS
    '保证每种视频模式的秒数、输入媒体数量和清晰度组合符合当前业务规则';
COMMENT ON CONSTRAINT chk_ai_model_usage_detail_metering_fields
    ON ai_model_usage_detail IS
    '限制Token、图片供应商成本和视频供应商成本三种预扣形态；视频形态由独立视频详情表及孤儿检查进一步保证完整性';

COMMIT;
