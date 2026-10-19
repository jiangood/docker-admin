-- 镜像仓库 → 镜像：一次性数据迁移
--
-- 执行时机：升级到本次改动后、启动新版本前。执行前请备份数据库并停止应用。
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
-- 0. 历史遗留表检查
--
-- 更早版本曾用 t_image 作为「镜像」实体表。若库中已存在 t_image（旧结构），
-- 直接执行第 1 步会失败或覆盖错误的数据。请先确认 t_image 是旧表还是空表；
-- 确认是旧表后，先改名备份：
--
-- ALTER TABLE t_image RENAME TO t_image_legacy;
-- ALTER TABLE t_image_version RENAME TO t_image_version_legacy;
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
