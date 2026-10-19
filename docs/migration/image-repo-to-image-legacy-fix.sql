-- 镜像仓库 → 镜像：生产库已存在历史遗留 t_image 时的修复脚本（手工备用方案）
--
-- 注意：新版本启动时会由 ImageTableMigrationEnvironmentPostProcessor 自动执行等价迁移，
--       正常情况下无需手工运行本脚本。仅在自动迁移失败需要手工介入时使用。
--
-- 适用于：升级后发现 t_image 是旧「镜像」模型表（没有 image_url 列），
--         打开镜像页面报 Column "I1_0.IMAGE_URL" not found。
--         包含两种情况：
--           1) 库里同时有 t_image_repo（当前镜像表）与旧 t_image；
--           2) 库里只有旧 t_image、没有 t_image_repo（从 4.7 之前直接升级）。
--
-- 执行前：
--   1. 停止应用（释放 H2 文件锁）
--   2. 备份数据库文件，如 data/db/docker-admin.mv.db
-- 兼容 H2（MODE=MySQL）与 MySQL 8。
--
-- 用 RENAME 而不是 CREATE TABLE ... AS SELECT + DROP：
--   数据原样保留；指向旧表的外键（如历史版本 t_app.image_id）会自动跟随改名，
--   不会因为 "Cannot drop T_IMAGE because FK... depends on it" 导致迁移失败。

-- 0. 诊断：确认 t_image 是旧表
-- SELECT table_name FROM information_schema.tables
--  WHERE table_schema='PUBLIC' AND table_name LIKE 'T_IMAGE%' ORDER BY table_name;
-- SELECT column_name FROM information_schema.columns
--  WHERE table_name='T_IMAGE' ORDER BY ordinal_position;

-- 1. 把遗留旧表改名让位，数据原样保留在 t_image_legacy
--    （若 t_image_legacy 已存在，改用 t_image_legacy_2、t_image_legacy_3 …）
ALTER TABLE t_image RENAME TO t_image_legacy;

-- 2. 若存在真正的镜像表 t_image_repo，把它改名为 t_image
--    （库里没有 t_image_repo 时跳过这一步，交由应用启动时按新实体重建）
ALTER TABLE t_image_repo RENAME TO t_image;

-- 3. 角色权限码与菜单 id
UPDATE sys_role
SET perms = REPLACE(perms, '"image-repo:', '"image:')
WHERE perms LIKE '%"image-repo:%';

UPDATE sys_role
SET menus = REPLACE(menus, '"image-repo"', '"image"')
WHERE menus LIKE '%"image-repo"%';

UPDATE sys_role
SET menus = REPLACE(menus, '"divider-image-repo"', '"divider-image"')
WHERE menus LIKE '%"divider-image-repo"%';

-- 4. 校验
-- SELECT table_name FROM information_schema.tables
--  WHERE table_schema='PUBLIC' AND table_name LIKE 'T_IMAGE%' ORDER BY table_name;
-- SELECT COUNT(*) FROM t_image;
-- SELECT COUNT(*) FROM t_image_legacy;
-- SELECT id, name, perms, menus FROM sys_role;
