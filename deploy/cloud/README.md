# 云端静态前端与 WireGuard API 回源

jd-edge-nginx 继续使用 host network 和现有证书，仅将 nexus.hjs321.site
的静态资源切换到云端本地磁盘；/api/ragent/ 仍经 WireGuard 回源
10.66.0.2:80。hjs321.site 和 swift.hjs321.site 的路由不变。

## 首次部署

在 Windows PowerShell 中执行：

    .\\scripts\\publish-cloud-static.ps1 `
      -CloudSshKey 'C:\\Users\\25082\\main_compute.pem'

脚本会执行 npm ci、生产构建、敏感值扫描、压缩上传、文件校验和 current
符号链接原子切换。云端保留最近 3 个 release；失败时不会切换当前版本。

云端边缘配置需要包含：

    /opt/jd-edge/nexus-static:/srv/nexus-static:ro

本目录中的 compose.yaml 和 nginx.conf 是可审阅的配置基线。应用配置前请备份
/opt/jd-edge/nginx.conf 与 /opt/jd-edge/compose.yaml，并执行 docker compose
config、docker exec jd-edge-nginx nginx -t。

## 回滚静态版本

先在云端查看可用目录：

    ls -1 /opt/jd-edge/nexus-static/releases

再执行：

    .\\scripts\\publish-cloud-static.ps1 `
      -Mode rollback `
      -RollbackReleaseId '20260829-abcdef1' `
      -CloudSshKey 'C:\\Users\\25082\\main_compute.pem'

回滚只切换静态文件，不修改家里业务容器和数据卷。
