-- 地点表：存储酒店、景点、餐厅等地点信息
-- AI查询后先入库，高德地图从此表获取地址

CREATE TABLE IF NOT EXISTS `place` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `name` VARCHAR(200) NOT NULL COMMENT '地点名称',
    `address` VARCHAR(500) COMMENT '详细地址（精确到门牌号）',
    `description` VARCHAR(500) COMMENT '简介',
    `type` VARCHAR(50) COMMENT '类型：hotel=酒店, attraction=景点, restaurant=餐厅',
    `city` VARCHAR(100) COMMENT '所属城市',
    `longitude` DECIMAL(10, 7) COMMENT '经度',
    `latitude` DECIMAL(10, 7) COMMENT '纬度',
    `price` VARCHAR(100) COMMENT '价格',
    `phone` VARCHAR(50) COMMENT '联系电话',
    `source` VARCHAR(50) COMMENT '数据来源：knowledge=知识库, web=联网搜索, manual=手动录入',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除：0=未删除，1=已删除',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_name` (`name`),
    INDEX `idx_type` (`type`),
    INDEX `idx_city` (`city`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地点表';
