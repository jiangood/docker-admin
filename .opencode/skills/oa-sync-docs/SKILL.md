---
name: oa-sync-docs
description: 在业务项目中同步 open-admin 框架的 skills + docs + AGENTS.md —— 从 GitHub Release 下载 framework-files.zip 并按规则覆盖写入到项目根目录。适用于以 Maven JAR + npm 包方式引入 open-admin 的业务项目。
---

# oa-sync-docs — 框架文件同步指南

## 适用范围

当业务项目已通过 Maven JAR（`io.github.jiangood:open-admin`）和 npm 包（`@jiangood/open-admin`）方式集成了 open-admin 框架，需要将框架的 skills（`.opencode/skills/`）、文档（`docs/open-admin/`）与 `AGENTS.md` 同步到项目根目录时使用。

触发时机：
- 升级框架版本后，将新版本的框架文件同步到业务项目
- 首次接入框架时，将框架文件生成到业务项目
- 框架文件缺失 / 被误删时，重新同步

## 前提条件检查

开始之前，必须确认：

1. **网络可用**：需能访问 `https://github.com`（下载 release ZIP）。
2. **工具可用**：`curl`、`unzip`（Windows 下也可用 PowerShell `Expand-Archive`，见下）。
3. **业务项目根目录**：通过 `pom.xml` 定位（向上查找最近的 `pom.xml`）。

## 同步规则

从 `https://github.com/jiangood/open-admin/releases/download/v{版本}/framework-files.zip` 下载框架文件，按以下规则写入业务项目根目录：

| 目标 | 规则 |
|------|------|
| `<根>/.opencode/skills/` | 仅覆盖框架 skill（`oa-crud`、`oa-upgrade`、`oa-sync-docs`、`oa-sonar-scan`），不删除业务本地 skill |
| `<根>/docs/open-admin/` | 全量镜像（删除该目录下 ZIP 之外的孤儿文件） |
| `<根>/AGENTS.md` | 不存在则生成；已存在且内容与框架新版不同时，展示 diff 询问开发者确认后再更新；新版本随 `docs/open-admin/AGENTS.md` 提供 |

- 同步按**内容比对**：无变更不写入。
- ZIP 内部结构：`docs/open-admin/**`、`.opencode/skills/**`。

## 执行流程

### 1. 确定框架版本

读取业务项目当前依赖的框架版本（`pom.xml` 中 `io.github.jiangood:open-admin` 的 `<version>`，或 `package.json` 中 `@jiangood/open-admin` 的版本）。若为升级场景，使用目标版本号；否则使用当前版本。版本号不带 `v` 前缀（如 `3.0.2`）。

> 如需同步的版本与业务项目依赖版本不同（例如回退 / 预演新版本），可让开发者显式指定版本号。

### 2. 下载 ZIP

```bash
# 替换 {VERSION} 为步骤 1 确定的版本号
curl -fL -o /tmp/oa-framework-files.zip \
  "https://github.com/jiangood/open-admin/releases/download/v{VERSION}/framework-files.zip"
```

- 若 404 / 下载失败：确认该版本 release 确实存在（`gh release view v{VERSION} --repo jiangood/open-admin`），或改用已发布的最近版本。
- ZIP 解压到临时目录：

```bash
rm -rf /tmp/oa-framework-files && mkdir -p /tmp/oa-framework-files
unzip -q /tmp/oa-framework-files.zip -d /tmp/oa-framework-files
```

### 3. 同步到项目根目录

以下命令在**业务项目根目录**执行（先确认 `pwd` 包含 `pom.xml`）。

```bash
PROJ_ROOT=$(pwd)
SRC=/tmp/oa-framework-files

# 3a. .opencode/skills/ —— 仅覆盖框架 skill，不删除本地 skill
mkdir -p "$PROJ_ROOT/.opencode/skills"
for skill in oa-crud oa-upgrade oa-sync-docs oa-sonar-scan; do
  if [ -d "$SRC/.opencode/skills/$skill" ]; then
    mkdir -p "$PROJ_ROOT/.opencode/skills/$skill"
    cp -r "$SRC/.opencode/skills/$skill/." "$PROJ_ROOT/.opencode/skills/$skill/"
  fi
done

# 3b. docs/open-admin/ —— 全量镜像（删除孤儿文件）
mkdir -p "$PROJ_ROOT/docs/open-admin"
cp -r "$SRC/docs/open-admin/." "$PROJ_ROOT/docs/open-admin/"
# 删除 ZIP 之外的孤儿文档（在 docs/open-admin 目录下执行）
cd "$PROJ_ROOT/docs/open-admin" || exit 1
find . -type f | while read -r f; do
  [ -f "$SRC/docs/open-admin/$f" ] || { echo "删除孤儿文档: $f"; rm "$f"; }
done
cd "$PROJ_ROOT" || exit 1

# 3c. 根目录 AGENTS.md —— 不存在则生成，存在则按内容比对，不同时询问开发者确认后更新
if [ -f "$SRC/docs/open-admin/AGENTS.md" ]; then
  if [ ! -f "$PROJ_ROOT/AGENTS.md" ]; then
    cp "$SRC/docs/open-admin/AGENTS.md" "$PROJ_ROOT/AGENTS.md"
    echo "生成 AGENTS.md（项目根目录）"
  elif ! cmp -s "$SRC/docs/open-admin/AGENTS.md" "$PROJ_ROOT/AGENTS.md"; then
    echo "检测到 AGENTS.md 与框架新版内容不同，差异如下："
    diff "$PROJ_ROOT/AGENTS.md" "$SRC/docs/open-admin/AGENTS.md" || true
    # 用 question 工具询问开发者是否更新（覆盖本地自定义内容前必须确认）
    echo "等待开发者确认后执行：cp $SRC/docs/open-admin/AGENTS.md $PROJ_ROOT/AGENTS.md"
  fi
fi
```

Windows 环境可改用 PowerShell：

```powershell
$src = "C:\Temp\oa-framework-files"
# 3a. 覆盖框架 skill
Copy-Item "$src\.opencode\skills\*" "$PWD\.opencode\skills\" -Recurse -Force
# 3b. 镜像 docs（含删除孤儿，酌情处理）
Copy-Item "$src\docs\open-admin\*" "$PWD\docs\open-admin\" -Recurse -Force
# 3c. AGENTS.md —— 不存在则生成，存在且不同时询问确认后更新
if (Test-Path "$src\docs\open-admin\AGENTS.md") {
  if (-not (Test-Path "$PWD\AGENTS.md")) {
    Copy-Item "$src\docs\open-admin\AGENTS.md" "$PWD\AGENTS.md"
  } elseif (-not (Compare-Object (Get-Content "$PWD\AGENTS.md") (Get-Content "$src\docs\open-admin\AGENTS.md") -SyncWindow 0)) {
    # 内容一致，无操作
  } else {
    Write-Host "AGENTS.md 与框架新版不同，先与开发者确认后再覆盖："
    Compare-Object (Get-Content "$PWD\AGENTS.md") (Get-Content "$src\docs\open-admin\AGENTS.md")
  }
}
```

### 4. 清理临时文件

```bash
rm -rf /tmp/oa-framework-files /tmp/oa-framework-files.zip
```

### 5. 验证

```bash
# 确认关键文件存在且为期望版本内容
ls -la .opencode/skills/oa-sync-docs/SKILL.md docs/open-admin/guide.md
# 抽查文档内容包含框架版本关键字（可选）
```

## 验证清单

- [ ] `.opencode/skills/oa-crud|oa-upgrade|oa-sync-docs|oa-sonar-scan/SKILL.md` 已更新
- [ ] `docs/open-admin/*.md` 已镜像，孤儿文件已删除
- [ ] `AGENTS.md` 不存在时已生成；已存在且与框架新版不同时，已询问开发者并更新（或开发者明确保留旧版）
- [ ] 无变更文件未被无谓写入

## 故障排查

| 问题 | 处理 |
|------|------|
| ZIP 下载 404 | 版本号错误或 release 未附带 ZIP；确认后重试 |
| `unzip` 不可用 | `apt-get install unzip` 或改用 PowerShell `Expand-Archive` |
| 业务项目无 pom.xml | 确认在正确的项目根目录执行 |
| 同步后文件无变化 | 符合预期（内容比对，无变更不写入） |

## 参考

- 框架仓库: https://github.com/jiangood/open-admin
- Release 资产命名约定：`framework-files.zip`（v{版本} release 自动附带）