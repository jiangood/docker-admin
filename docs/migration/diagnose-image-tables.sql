-- 诊断：镜像相关表结构（升级 t_image 冲突时先执行这个）
--
-- 期望结果：
--   1) 同时存在 T_IMAGE_REPO（当前镜像表）与 T_IMAGE（更早版本遗留的旧表）；
--      且 T_IMAGE 没有 IMAGE_URL 列 —— 这就是「Column I1_0.IMAGE_URL not found」的原因。
--   2) 若 T_IMAGE_REPO 不存在，说明库里没有镜像数据，可直接备份并删除旧 T_IMAGE，
--      由应用启动时重建（无需执行 legacy-fix 里的改名步骤）。

SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'PUBLIC'
  AND table_name LIKE 'T_IMAGE%'
ORDER BY table_name;

SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = 'PUBLIC'
  AND table_name IN ('T_IMAGE', 'T_IMAGE_REPO')
ORDER BY table_name, ordinal_position;
