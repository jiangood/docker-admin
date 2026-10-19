-- 镜像仓库 → 镜像：生产库已存在历史遗留 t_image 时的修复脚本（手工备用方案）
--
-- 注意：新版本启动时会由 ImageTableMigrationEnvironmentPostProcessor 自动执行等价迁移，
--       正常情况下无需手工运行本脚本。仅在自动迁移失败需要手工介入时使用。
--
-- 适用于：升级后发现 t_image 是旧「镜像」模型表（没有 image_url 列），
--         打开镜像页面报 Column "I1_0.IMAGE_URL" not found。
--
-- 执行前：
--   1. 停止应用（释放 H2 文件锁）
--   2. 备份数据库文件，如 data/db/docker-admin.mv.db
-- 兼容 H2（MODE=MySQL）与 MySQL 8。

-- 0. 诊断：确认 t_image 是旧表、t_image_repo 存在且是当前镜像表
-- SELECT table_name FROM information_schema.tables
--  WHERE table_schema='PUBLIC' AND table_name LIKE 'T_IMAGE%' ORDER BY table_name;
-- SELECT column_name FROM information_schema.columns
--  WHERE table_name='T_IMAGE' ORDER BY ordinal_position;
-- SELECT COUNT(*) FROM t_image_repo;

-- 1. 备份遗留表数据
--    用 CREATE TABLE AS SELECT 而不是 RENAME：备份表不携带原表的约束，
--    避免遗留表上的外键/唯一约束名与新建的 t_image 冲突。
CREATE TABLE t_image_legacy AS SELECT * FROM t_image;

-- 2. 核对行数一致后再继续（不一致就停下来排查，不要 DROP）
-- SELECT COUNT(*) FROM t_image;
-- SELECT COUNT(*) FROM t_image_legacy;

-- 3. 删除遗留表，释放表名与约束名
DROP TABLE t_image;

-- 4. 把真正的镜像表改名为 t_image
ALTER TABLE t_image_repo RENAME TO t_image;

-- 5. 角色权限码与菜单 id
UPDATE sys_role
SET perms = REPLACE(perms, '"image-repo:', '"image:')
WHERE perms LIKE '%"image-repo:%';

UPDATE sys_role
SET menus = REPLACE(menus, '"image-repo"', '"image"')
WHERE menus LIKE '%"image-repo"%';

UPDATE sys_role
SET menus = REPLACE(menus, '"divider-image-repo"', '"divider-image"')
WHERE menus LIKE '%"divider-image-repo"%';

-- 6. 校验
-- SELECT id, name, perms, menus FROM sys_role;
-- SELECT COUNT(*) FROM t_image;
