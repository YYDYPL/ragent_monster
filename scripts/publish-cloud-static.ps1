[CmdletBinding()]
param(
    [ValidateSet('publish', 'rollback')]
    [string]$Mode = 'publish',

    [string]$CloudHost = '111.228.25.159',
    [string]$CloudUser = 'root',
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$CloudSshKey,
    [string]$ReleaseId,
    [string]$RollbackReleaseId
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$frontendDir = Join-Path $repoRoot 'frontend'
$distDir = Join-Path $frontendDir 'dist'
$cloudTarget = "$CloudUser@$CloudHost"
$sshArgs = @('-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new', '-i', $CloudSshKey)
$previousApiBaseUrl = $env:VITE_API_BASE_URL

function Invoke-Remote {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [switch]$AllowFailure
    )

    & ssh @sshArgs $cloudTarget $Command
    if (-not $AllowFailure -and $LASTEXITCODE -ne 0) {
        throw "远程命令执行失败（退出码 $LASTEXITCODE）。"
    }
}

function Get-ReleaseId {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $sha = (& git -C $repoRoot rev-parse --short=7 HEAD 2>$null).Trim()
    if ([string]::IsNullOrWhiteSpace($sha)) { $sha = 'nogit' }
    return "$timestamp-$sha"
}

if (-not (Test-Path -LiteralPath $CloudSshKey -PathType Leaf)) {
    throw "找不到 SSH 私钥：$CloudSshKey"
}

if ($Mode -eq 'rollback') {
    if ([string]::IsNullOrWhiteSpace($RollbackReleaseId) -or $RollbackReleaseId -notmatch '^[A-Za-z0-9._-]+$') {
        throw '回滚时必须提供安全的 -RollbackReleaseId。'
    }
    $rollbackCommand = @'
set -eu
base=/opt/jd-edge/nexus-static
release='__RELEASE__'
test -f "$base/releases/$release/index.html"
previous="$(readlink "$base/current" 2>/dev/null || true)"
tmp="$base/.current.rollback.$release.$$"
ln -s "releases/$release" "$tmp"
mv -Tf "$tmp" "$base/current"
test -L "$base/current"
docker exec jd-edge-nginx nginx -t
docker exec jd-edge-nginx nginx -s reload
if ! curl -fsSk --max-time 15 https://nexus.hjs321.site/ -o /dev/null; then
  if [ -n "$previous" ] && [ -d "$base/$previous" ]; then
    restore="$base/.current.restore.$$"
    ln -s "$previous" "$restore"
    mv -Tf "$restore" "$base/current"
    docker exec jd-edge-nginx nginx -s reload
  fi
  echo "静态回滚烟雾测试失败，已恢复上一版本（如存在）。" >&2
  exit 1
fi
echo "ROLLBACK_OK $release"
'@
    $rollbackCommand = $rollbackCommand.Replace('__RELEASE__', $RollbackReleaseId)
    Invoke-Remote -Command $rollbackCommand
    Write-Host "已将云端静态资源回滚到 $RollbackReleaseId。"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($ReleaseId)) { $ReleaseId = Get-ReleaseId }
if ($ReleaseId -notmatch '^[A-Za-z0-9._-]+$') { throw "ReleaseId 含有不安全字符：$ReleaseId" }

Push-Location $frontendDir
try {
    Write-Host '安装前端依赖并构建生产资源...'
    $env:VITE_API_BASE_URL = '/api/ragent'
    npm ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw "npm ci 失败（退出码 $LASTEXITCODE）。" }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "前端构建失败（退出码 $LASTEXITCODE）。" }
}
finally {
    Pop-Location
    if ($null -eq $previousApiBaseUrl) {
        Remove-Item Env:VITE_API_BASE_URL -ErrorAction SilentlyContinue
    } else {
        $env:VITE_API_BASE_URL = $previousApiBaseUrl
    }
}

if (-not (Test-Path -LiteralPath $distDir -PathType Container)) { throw "构建未生成 dist：$distDir" }
foreach ($required in @('index.html', 'assets', 'branding')) {
    if (-not (Test-Path -LiteralPath (Join-Path $distDir $required))) {
        throw "dist 缺少发布必需项：$required"
    }
}

Write-Host '扫描静态资源，确认没有密钥或敏感配置...'
$secretPattern = '(?i)(?:\b(?:sk|ydc-sk)-[A-Za-z0-9_-]{20,}\b|\b(?:LIGHTRAG_API_KEY|DEEPSEEK_API_KEY|YDC_API_KEY|MINERU_API_KEY|BAILIAN_API_KEY|DASHSCOPE_API_KEY)\s*[:=])'
$secretHits = & rg --hidden --no-ignore-vcs --text -n $secretPattern $distDir 2>$null
if ($LASTEXITCODE -eq 0 -and $secretHits) {
    throw "dist 中检测到疑似密钥或敏感配置：$secretHits"
}

$archive = Join-Path ([System.IO.Path]::GetTempPath()) "nexus-static-$ReleaseId.tar.gz"
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
try {
    Write-Host "打包 $ReleaseId ..."
    tar -czf $archive -C $distDir .
    if ($LASTEXITCODE -ne 0) { throw "静态资源打包失败（退出码 $LASTEXITCODE）。" }

    Write-Host '上传到云服务器临时目录...'
    & scp @sshArgs $archive "$cloudTarget`:/tmp/"
    if ($LASTEXITCODE -ne 0) { throw "静态资源上传失败（退出码 $LASTEXITCODE）。" }

    $remoteCommand = @'
set -eu
base=/opt/jd-edge/nexus-static
release='__RELEASE__'
archive=/tmp/nexus-static-__RELEASE__.tar.gz
mkdir -p "$base/releases" "$base/.incoming"
test ! -e "$base/releases/$release"
previous="$(readlink "$base/current" 2>/dev/null || true)"
tmpdir="$base/.incoming/$release.$$"
mkdir "$tmpdir"
tar -xzf "$archive" -C "$tmpdir"
test -f "$tmpdir/index.html"
test -d "$tmpdir/assets"
test -d "$tmpdir/branding"
mv "$tmpdir" "$base/releases/$release"
linktmp="$base/.current.$$"
ln -s "releases/$release" "$linktmp"
mv -Tf "$linktmp" "$base/current"
rm -f "$archive"
find "$base/releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' | sort -nr | tail -n +4 | cut -d' ' -f2- | xargs -r rm -rf --
test -L "$base/current"
if ! curl -fsSk --max-time 15 https://nexus.hjs321.site/ -o /dev/null; then
  if [ -n "$previous" ] && [ -d "$base/$previous" ]; then
    restore="$base/.current.restore.$$"
    ln -s "$previous" "$restore"
    mv -Tf "$restore" "$base/current"
  fi
  echo "静态首页烟雾测试失败，已恢复上一版本（如存在）。" >&2
  exit 1
fi
echo "PUBLISH_OK $release"
'@
    $remoteCommand = $remoteCommand.Replace('__RELEASE__', $ReleaseId)
    Invoke-Remote -Command $remoteCommand
}
finally {
    if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue }
}

Write-Host "云端静态资源发布完成：$ReleaseId"
