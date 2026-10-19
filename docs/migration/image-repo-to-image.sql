-- 镜像仓库 → 镜像：一次性数据迁移（手工备用方案）
--
-- 注意：新版本启动时会由 ImageTableMigrationEnvironmentPostProcessor 自动完成迁移，
--       正常情况下无需手工执行本脚本。
--       仅在自动迁移失败、或需要手工处理 MySQL 时使用。
--
-- 执行时机：停止应用后执行（H2 需先停服释放文件锁）。执行前请备份数据库。
-- 兼容 H2（MODE=MySQL）与 MySQL 8。
--
-- 本次改动涉及：
--   表名        t_image_repo            → t_image
--   权限码      image-repo:*            → image:*
--   角色菜单 id image-repo / divider-image-repo → image / divider-image
--
-- 注意：更早版本的 Image 模块也使用过 image:* 权限码和 image 菜单 id。
-- 若库中仍有当年残留的授权，升级后会被视为新「镜像」模块的权限/菜单。
-- 如不希望这些角色自动获得访问权，请先检查并清理 sys_role.perms / sys_role.menus。

-- ---------------------------------------------------------------------------
-- 0. 历史遗留表检查（重要）
--
-- 更早版本的「镜像」实体也用 t_image 作为表名（旧结构，没有 image_url 列）。
-- 若库中已存在这样的旧 t_image，直接执行第 1 步会失败；
-- 应用启动后打开镜像页面会报 "Column I1_0.IMAGE_URL not found"。
--
-- 先诊断：
--   SELECT table_name FROM information_schema.tables
--    WHERE table_schema='PUBLIC' AND table_name LIKE 'T_IMAGE%';
--   SELECT column_name FROM information_schema.columns WHERE table_name='T_IMAGE';
--
-- 若确认是遗留旧表，请改用 docs/migration/image-repo-to-image-legacy-fix.sql。
-- ---------------------------------------------------------------------------

-- 1. 表名
ALTER TABLE t_image_repo RENAME TO t_image;

-- 2. 角色权限码（perms 为 JSON 数组文本）
UPDATE sys_role
SET perms = REPLACE(perms, '"image-repo:', '"image:')
WHERE perms LIKE '%"image-repo:%';

-- 3. 角色菜单 id（menus 为 JSON 数组文本）
UPDATE sys_role
SET menus = REPLACE(menus, '"image-repo"', '"image"')
WHERE menus LIKE '%"image-repo"%';

UPDATE sys_role
SET menus = REPLACE(menus, '"divider-image-repo"', '"divider-image"')
WHERE menus LIKE '%"divider-image-repo"%';

-- 4. 校验（可选）
-- SELECT id, name, perms, menus FROM sys_role;
-- SELECT COUNT(*) FROM t_image;
