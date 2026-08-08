BEGIN;

ALTER TABLE ai_conversation_generation
	ADD COLUMN video_stage VARCHAR(48),
	ADD CONSTRAINT chk_ai_conversation_generation_video_stage CHECK (
		video_stage IS NULL OR video_stage IN (
			'QUEUED', 'VALIDATING_MEDIA', 'RESERVED', 'XAI_SUBMITTING',
			'XAI_PENDING', 'XAI_DONE', 'OSS_TRANSFERRING', 'OSS_READY',
			'SUCCEEDED', 'MEDIA_VALIDATION_FAILED', 'XAI_REJECTED',
			'XAI_FAILED', 'XAI_EXPIRED', 'XAI_RESULT_UNCERTAIN',
			'OSS_TRANSFER_FAILED', 'BILLING_RECONCILE_REQUIRED'));

COMMENT ON COLUMN ai_conversation_generation.video_stage IS
	'视频任务跨 xAI 与 OSS 的安全阶段；普通对话和图片任务为空，不保存临时 URL 或授权信息';

ALTER TABLE ai_model_capability
    DROP CONSTRAINT chk_ai_model_capability_code,
    ADD CONSTRAINT chk_ai_model_capability_code
        CHECK (capability_code IN (
            'CHAT_COMPLETIONS',
            'RESPONSES',
            'WEB_SEARCH',
            'IMAGE_INPUT',
            'IMAGE_GENERATION',
            'IMAGE_EDIT',
            'AUDIO_INPUT',
            'AUDIO_GENERATION',
            'AUDIO_EDIT',
            'VIDEO_INPUT',
            'VIDEO_GENERATION',
            'VIDEO_EDIT',
            'VIDEO_EXTENSION'
        ));

ALTER TABLE ai_model_usage_detail
    ADD COLUMN video_mode VARCHAR(32),
    ADD COLUMN video_resolution VARCHAR(16),
    ADD COLUMN requested_duration_seconds INTEGER,
    ADD COLUMN input_image_count INTEGER,
    ADD COLUMN input_video_duration_millis BIGINT,
    ADD COLUMN output_cost_ticks_per_second BIGINT,
    ADD COLUMN image_input_cost_ticks_each BIGINT,
    ADD COLUMN video_input_cost_ticks_per_second BIGINT,
    ADD COLUMN estimated_provider_cost_ticks BIGINT;

ALTER TABLE ai_model_usage_detail
    DROP CONSTRAINT chk_ai_model_usage_detail_metering_fields,
    ADD CONSTRAINT chk_ai_model_usage_detail_video_mode
        CHECK (video_mode IS NULL OR video_mode IN (
            'TEXT_TO_VIDEO',
            'IMAGE_TO_VIDEO',
            'REFERENCE_TO_VIDEO',
            'VIDEO_EDIT',
            'VIDEO_EXTEND'
        )),
    ADD CONSTRAINT chk_ai_model_usage_detail_video_resolution
        CHECK (video_resolution IS NULL OR video_resolution IN ('P480', 'P720', 'P1080')),
    ADD CONSTRAINT chk_ai_model_usage_detail_video_numbers
        CHECK (
            (requested_duration_seconds IS NULL OR requested_duration_seconds >= 0)
            AND (input_image_count IS NULL OR input_image_count BETWEEN 0 AND 7)
            AND (input_video_duration_millis IS NULL OR input_video_duration_millis >= 0)
            AND (output_cost_ticks_per_second IS NULL OR output_cost_ticks_per_second >= 0)
            AND (image_input_cost_ticks_each IS NULL OR image_input_cost_ticks_each >= 0)
            AND (video_input_cost_ticks_per_second IS NULL OR video_input_cost_ticks_per_second >= 0)
            AND (estimated_provider_cost_ticks IS NULL OR estimated_provider_cost_ticks >= 0)
        ),
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
                AND video_mode IS NULL
                AND video_resolution IS NULL
                AND requested_duration_seconds IS NULL
                AND input_image_count IS NULL
                AND input_video_duration_millis IS NULL
                AND output_cost_ticks_per_second IS NULL
                AND image_input_cost_ticks_each IS NULL
                AND video_input_cost_ticks_per_second IS NULL
                AND estimated_provider_cost_ticks IS NULL
            )
            OR (
                metering_basis = 1
                AND estimated_prompt_tokens IS NULL
                AND max_output_tokens IS NULL
                AND input_ratio_snapshot IS NULL
                AND cached_input_ratio_snapshot IS NULL
                AND output_ratio_snapshot IS NULL
                AND (
                    (
                        requested_output_count BETWEEN 1 AND 10
                        AND video_mode IS NULL
                        AND video_resolution IS NULL
                        AND requested_duration_seconds IS NULL
                        AND input_image_count IS NULL
                        AND input_video_duration_millis IS NULL
                        AND output_cost_ticks_per_second IS NULL
                        AND image_input_cost_ticks_each IS NULL
                        AND video_input_cost_ticks_per_second IS NULL
                        AND estimated_provider_cost_ticks IS NULL
                    )
                    OR (
                        requested_output_count IS NULL
                        AND video_mode IS NOT NULL
                        AND video_resolution IS NOT NULL
                        AND requested_duration_seconds IS NOT NULL
                        AND input_image_count IS NOT NULL
                        AND input_video_duration_millis IS NOT NULL
                        AND output_cost_ticks_per_second IS NOT NULL
                        AND image_input_cost_ticks_each IS NOT NULL
                        AND video_input_cost_ticks_per_second IS NOT NULL
                        AND estimated_provider_cost_ticks IS NOT NULL
                    )
                )
            )
        );

COMMENT ON COLUMN ai_model_usage_detail.video_mode IS
    '视频成本计量请求冻结的五种模式；非视频请求必须为空';
COMMENT ON COLUMN ai_model_usage_detail.video_resolution IS
    '视频请求预扣使用的清晰度档位；编辑和延长保存可信输入继承档位';
COMMENT ON COLUMN ai_model_usage_detail.requested_duration_seconds IS
    '视频生成或延长请求的秒数；编辑模式为零';
COMMENT ON COLUMN ai_model_usage_detail.input_image_count IS
    '视频请求实际使用的输入图片数量';
COMMENT ON COLUMN ai_model_usage_detail.input_video_duration_millis IS
    '经可信媒体探测得到的输入视频毫秒时长';
COMMENT ON COLUMN ai_model_usage_detail.output_cost_ticks_per_second IS
    '预扣时冻结的官方视频输出每秒美元成本 ticks';
COMMENT ON COLUMN ai_model_usage_detail.image_input_cost_ticks_each IS
    '预扣时冻结的官方图片输入每张美元成本 ticks';
COMMENT ON COLUMN ai_model_usage_detail.video_input_cost_ticks_per_second IS
    '预扣时冻结的官方视频输入每秒美元成本 ticks';
COMMENT ON COLUMN ai_model_usage_detail.estimated_provider_cost_ticks IS
    '根据冻结价格与输入规模计算的预计供应商美元成本 ticks';

COMMIT;
