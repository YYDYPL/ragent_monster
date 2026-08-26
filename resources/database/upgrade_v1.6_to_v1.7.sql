-- ragent v1.6 -> v1.7 升级脚本
-- 新增公开体验游客账号；可重复执行，不修改其他用户

INSERT INTO t_user (id, username, password, role, avatar, create_time, update_time, deleted)
VALUES ('2001523723396308994', 'guest', 'guest', 'guest', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (username) DO UPDATE
SET password = EXCLUDED.password,
    role = EXCLUDED.role,
    avatar = EXCLUDED.avatar,
    update_time = CURRENT_TIMESTAMP,
    deleted = 0;

COMMENT ON COLUMN t_user.role IS '角色：admin/user/guest';
