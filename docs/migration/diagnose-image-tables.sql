-- 诊断：镜像相关表结构（升级 t_image 冲突时先执行这个）
--
-- 期望结果：
--   1) 同时存在 T_IMAGE_REPO（当前镜像表）与 T_IMAGE（更早版本遗留的旧表）；
--      且 T_IMAGE 没有 IMAGE_URL 列 —— 这就是「Column I1_0.IMAGE_URL not found」的原因。
--   2) 只存在 T_IMAGE 且没有 IMAGE_URL 列、没有 T_IMAGE_REPO —— 从 4.7 之前直接升级的库，
--      同样是「Column I1_0.IMAGE_URL not found」的原因。
--   3) 若 T_IMAGE_REPO 不存在、T_IMAGE 结构也已是新的（有 IMAGE_URL），说明迁移已完成。
--
-- 以上两种历史结构，新版本启动时都会由 ImageTableMigrationEnvironmentPostProcessor
-- 自动处理：把旧 T_IMAGE 改名为 T_IMAGE_LEGACY，再把 T_IMAGE_REPO 改名为 T_IMAGE（若有）。
-- 若启动日志里出现 [image-migration] 自动迁移失败，再使用
-- docs/migration/image-repo-to-image-legacy-fix.sql 手工处理。

SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'PUBLIC'
  AND table_name LIKE 'T_IMAGE%'
ORDER BY table_name;

SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = 'PUBLIC'
  AND table_name IN ('T_IMAGE', 'T_IMAGE_REPO', 'T_IMAGE_LEGACY')
ORDER BY table_name, ordinal_position;
