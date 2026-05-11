-- 1. travel_plan 表（旅游计划主表）
CREATE TABLE IF NOT EXISTS `travel_plan` (
                                             `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法生成）',
                                             `user_id` BIGINT NOT NULL COMMENT '用户ID',
                                             `conversation_id` BIGINT DEFAULT NULL COMMENT '关联对话ID',
                                             `title` VARCHAR(200) NOT NULL DEFAULT '我的旅行计划' COMMENT '计划名称',
    `destination` VARCHAR(100) DEFAULT NULL COMMENT '目的地城市',
    `start_date` DATE DEFAULT NULL COMMENT '出发日期',
    `end_date` DATE DEFAULT NULL COMMENT '返回日期',
    `status` INT DEFAULT 1 COMMENT '状态：1=草稿，2=已确认，3=已导出PDF',
    `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0=未删除，1=已删除',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_deleted` (`deleted`),
    INDEX `idx_create_time` (`create_time`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='旅游计划主表';

-- 2. plan_item 表（计划项表：存储每天的每个地点）
-- 通过 place_id 关联 place 表获取 name, address, type, city, longitude, latitude, price 等详细信息
CREATE TABLE IF NOT EXISTS `plan_item` (
                                           `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                                           `plan_id` BIGINT NOT NULL COMMENT '所属计划ID（关联 travel_plan.id）',
                                           `place_id` BIGINT DEFAULT NULL COMMENT '关联地点ID（关联 place.id）',
                                           `day_date` VARCHAR(20) DEFAULT NULL COMMENT '当天日期（如：2026-04-15）',
    `sort_order` INT DEFAULT 0 COMMENT '当天内的排序权重',
    `notes` TEXT DEFAULT NULL COMMENT '备注',
    `duration` VARCHAR(50) DEFAULT NULL COMMENT '建议游览时长',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_plan_id` (`plan_id`),
    INDEX `idx_place_id` (`place_id`),
    INDEX `idx_day_date` (`day_date`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计划项表（每天每个地点）';