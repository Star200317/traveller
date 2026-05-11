-- ============================================================
-- AI旅游向导智能体 数据库初始化脚本
-- ============================================================

CREATE DATABASE IF NOT EXISTS ai_travel DEFAULT CHARACTER SET utf8mb4;
USE ai_travel;

-- ------------------------------------------------------------
-- 用户表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL COMMENT '用户ID（雪花）',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt）',
    `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `avatar`      VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1正常 0禁用',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ------------------------------------------------------------
-- 会话表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `conversation` (
    `id`          BIGINT       NOT NULL COMMENT '会话ID',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `title`       VARCHAR(100) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1进行中 2已完成 3已关闭',
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话表';

-- ------------------------------------------------------------
-- 消息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `message` (
    `id`              BIGINT        NOT NULL COMMENT '消息ID',
    `conversation_id` BIGINT        NOT NULL COMMENT '会话ID',
    `role`            VARCHAR(20)   NOT NULL COMMENT 'user/assistant/tool',
    `content`         LONGTEXT      NOT NULL COMMENT '消息内容',
    `tool_calls`      JSON          DEFAULT NULL COMMENT '工具调用记录',
    `tokens`          INT           DEFAULT 0 COMMENT '消耗token数',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表';

-- ------------------------------------------------------------
-- 旅游计划表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `travel_plan` (
    `id`              BIGINT       NOT NULL COMMENT '计划ID',
    `user_id`         BIGINT       NOT NULL COMMENT '用户ID',
    `conversation_id` BIGINT       NOT NULL COMMENT '关联会话ID',
    `title`           VARCHAR(200) NOT NULL COMMENT '计划标题',
    `destination`     VARCHAR(100) NOT NULL COMMENT '目的地',
    `start_date`      DATE         DEFAULT NULL COMMENT '出发日期',
    `end_date`        DATE         DEFAULT NULL COMMENT '返回日期',
    `days`            INT          DEFAULT NULL COMMENT '天数',
    `budget`          DECIMAL(10,2) DEFAULT NULL COMMENT '预算（元）',
    `travel_style`    VARCHAR(50)  DEFAULT NULL COMMENT '旅行风格',
    `people_count`    INT          DEFAULT 1 COMMENT '出行人数',
    `plan_content`    JSON         NOT NULL COMMENT '计划详情JSON',
    `map_data`        JSON         DEFAULT NULL COMMENT '地图数据JSON',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '1草稿 2已确认 3已导出',
    `deleted`         TINYINT      NOT NULL DEFAULT 0,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旅游计划表';

-- ------------------------------------------------------------
-- 每日行程表（plan_content的结构化存储）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `travel_day` (
    `id`           BIGINT       NOT NULL COMMENT '行程ID',
    `plan_id`      BIGINT       NOT NULL COMMENT '计划ID',
    `day_index`    INT          NOT NULL COMMENT '第几天',
    `day_date`     DATE         DEFAULT NULL COMMENT '具体日期',
    `title`        VARCHAR(200) DEFAULT NULL COMMENT '当天主题',
    `attractions`  JSON         DEFAULT NULL COMMENT '景点列表',
    `transport`    JSON         DEFAULT NULL COMMENT '交通信息',
    `accommodation`VARCHAR(200) DEFAULT NULL COMMENT '住宿',
    `budget`       DECIMAL(10,2) DEFAULT NULL,
    `notes`        TEXT         DEFAULT NULL COMMENT '备注',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_plan_id` (`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日行程表';

-- ------------------------------------------------------------
-- 行程明细表（每行表格 = 一条记录，含经纬度供地图直接查询）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `plan_item` (
    `id`            BIGINT       NOT NULL COMMENT '明细ID（雪花）',
    `plan_id`       BIGINT       NOT NULL COMMENT '关联计划ID',
    `day_index`     INT          NOT NULL COMMENT '第几天（1-based）',
    `item_type`     VARCHAR(20)  NOT NULL COMMENT '类型：attraction景点/meal餐食/hotel酒店',
    `item_order`    INT          NOT NULL DEFAULT 0 COMMENT '当天排序序号（按时间顺序）',
    `name`          VARCHAR(200) NOT NULL COMMENT '名称（景点名/店名/酒店名）',
    `time_slot`     VARCHAR(50)  DEFAULT NULL COMMENT '时间段，如 07:30-08:30',
    `cost`          VARCHAR(50)  DEFAULT NULL COMMENT '花费，如 30元/人、免费、成人50元',
    `address`       VARCHAR(500) DEFAULT NULL COMMENT '精确地址（门牌号级别）',
    `description`   TEXT         DEFAULT NULL COMMENT '简介/推荐语',
    `latitude`      DOUBLE       DEFAULT NULL COMMENT '纬度（高德地图坐标系）',
    `longitude`     DOUBLE       DEFAULT NULL COMMENT '经度（高德地图坐标系）',
    `extra_info`    JSON         DEFAULT NULL COMMENT '扩展信息（ticket/price/recommendation等）',
    `deleted`       TINYINT      NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_plan_id` (`plan_id`),
    KEY `idx_plan_day` (`plan_id`, `day_index`),
    KEY `idx_plan_type` (`plan_id`, `day_index`, `item_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行程明细表（结构化存储每行行程项）';

-- ------------------------------------------------------------
-- 知识库文档索引表（RAG文档管理）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_doc` (
    `id`          BIGINT       NOT NULL COMMENT '文档ID',
    `user_id`     BIGINT       DEFAULT NULL COMMENT '用户ID（NULL=公共库）',
    `category`    VARCHAR(50)  NOT NULL COMMENT '分类：attraction/city/tip/user',
    `title`       VARCHAR(200) NOT NULL COMMENT '文档标题',
    `content`     LONGTEXT     NOT NULL COMMENT '原始内容',
    `file_name`   VARCHAR(255) DEFAULT NULL COMMENT '原始文件名',
    `file_size`   BIGINT       DEFAULT NULL,
    `pinecone_ids`JSON         DEFAULT NULL COMMENT '对应的Pinecone向量ID列表',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1已向量化 0待处理',
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档索引';

-- ------------------------------------------------------------
-- 工具调用日志表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tool_call_log` (
    `id`              BIGINT       NOT NULL COMMENT '日志ID',
    `conversation_id` BIGINT       NOT NULL COMMENT '会话ID',
    `tool_name`       VARCHAR(50)  NOT NULL COMMENT '工具名称',
    `input`           JSON         DEFAULT NULL COMMENT '输入参数',
    `output`          LONGTEXT     DEFAULT NULL COMMENT '输出结果',
    `success`         TINYINT      NOT NULL DEFAULT 1,
    `cost_ms`         INT          DEFAULT NULL COMMENT '耗时毫秒',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具调用日志';
