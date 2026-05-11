-- 为 travel_plan 表添加 plan_content 字段（JSON格式，存储计划内容）
ALTER TABLE `travel_plan` 
ADD COLUMN `plan_content` JSON DEFAULT NULL COMMENT '计划内容（JSON格式，包含days和items）' 
AFTER `status`;
