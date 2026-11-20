# oa-upgrade-docs — 框架文件同步指南

## 适用范围

当业务项目已通过 Maven JAR（`io.github.jiangood:open-admin`）和 npm 包（`@jiangood/open-admin`）方式集成了 open-admin 框架，需要将框架文件同步到项目根目录时使用。

同步内容**以目标 tag 源码中的 `framework-sync.json` 清单为准**（当前包含框架 skill 与 `docs/open-admin/` 文档；后续增减以清单为准）。

触发时机：
- 升级框架版本后，将新版本的框架文件同步到业务项目
- 首次接入框架时，将框架文件生成到业务项目
- 框架文件缺失 / 被误删时，重新同步
- 历史项目的本地同步 skill 过旧、无法识别新流程时，先按「历史项目引导/自举」手工同步一次

## 前提条件检查

1. **网络可用**：需能访问 `https://github.com`（下载 tag 源码归档）。
2. **工具可用**：`curl`、`tar`；执行清单驱动同步需 `python3`（推荐，3.8+）。Windows 下可用 PowerShell + `tar`，或在具备上述工具的环境执行。
3. **业务项目根目录**：通过 `pom.xml` 定位（向上查找最近的 `pom.xml`），以下同步命令默认在该目录执行。

## 文件来源（统一：tag 源码归档 + 清单）

- **来源**：GitHub tag 源码归档 `https://github.com/jiangood/open-admin/archive/refs/tags/{TAG}.tar.gz`（正式版与快照版统一，不再使用 `framework-files.zip`）。
- **清单**：解包后的 `docs/framework-sync.json`。每个条目包含 `path` 与 `mode`：
  - `mirror`：使目标路径与来源路径一致（目录内递归复制并删除来源中不存在的文件；文件则覆盖）。**仅作用于该条目路径本身**，不会删除清单外的其他文件/目录（例如不会影响 `.opencode/skills/` 下业务自建 skill）。
  - `copy`：将来源复制/覆盖到目标（目录递归合并覆盖，文件直接覆盖），不删除目标中额外文件。

> 说明：正式版 tag 为 `vX.Y.Z`，快照版 tag 为 `snapshot-vX.Y.Z-{build}`。两者都能通过 tag 归档获取对应版本文件，因此快照版同样可同步。

## 执行流程

### 1. 确定目标 tag

读取业务项目当前依赖的框架版本（`pom.xml` 中 `io.github.jiangood:open-admin` 的 `<version>`，或 `package.json` 中 `@jiangood/open-admin` 的版本）。若为升级场景，使用目标版本号；若需同步与依赖不同的版本（回退/预演），由开发者显式指定版本号或 tag。

按传入值换算 tag：

| 传入 | 通道 | tag |
|---|---|---|
| `3.4.0` / `v3.4.0` | 正式版 | `v3.4.0` |
| `snapshot-v3.4.0-1` | 快照版 | 原样使用 |
| `3.4.0-SNAPSHOT.1` | 快照版 | `snapshot-v3.4.0-1` |
| `3.4.0-SNAPSHOT`（无 build 号） | 快照版 | 询问开发者要哪个 build；或列出最新：`git ls-remote --tags --sort=-v:refname https://github.com/jiangood/open-admin.git 'refs/tags/snapshot-v3.4.0-*' \| head -1` |

换算规则：`x.y.z-SNAPSHOT.<build>` → `snapshot-v{x.y.z}-{build}`。

### 2. 校验 tag 并下载解包

```bash
# 替换 {TAG}，如 v3.4.0 或 snapshot-v3.4.0-1
TAG="{TAG}"

# 校验 tag 存在
git ls-remote --exit-code --tags https://github.com/jiangood/open-admin.git "refs/tags/$TAG" >/dev/null \
  || curl -fsS -o /dev/null "https://api.github.com/repos/jiangood/open-admin/git/ref/tags/$TAG" \
  || { echo "tag 不存在或无法访问: $TAG，停止同步"; exit 1; }

# 下载并解包（源码包顶层为 open-admin-{TAG}/，用 --strip-components=1 去掉）
rm -rf /tmp/oa-framework-files /tmp/oa-framework-files.tar.gz
mkdir -p /tmp/oa-framework-files
curl -fL --retry 3 -o /tmp/oa-framework-files.tar.gz \
  "https://github.com/jiangood/open-admin/archive/refs/tags/$TAG.tar.gz"
tar -xzf /tmp/oa-framework-files.tar.gz -C /tmp/oa-framework-files --strip-components=1
```

解包后必须校验清单与关键文件存在（防止来源异常）：

```bash
test -f /tmp/oa-framework-files/docs/framework-sync.json \
  && test -f /tmp/oa-framework-files/docs/open-admin/guide.md \
  && test -f /tmp/oa-framework-files/.opencode/skills/oa-upgrade-docs/SKILL.md \
  || { echo "来源目录结构或清单异常，停止同步"; exit 1; }
```

### 3. 按清单同步到业务项目根目录

在**业务项目根目录**执行（确认当前目录存在 `pom.xml`）。以下脚本读取来源中的 `framework-sync.json` 并按条目同步：

```bash
python3 - <<'PY'
import json, os, shutil
from pathlib import Path

PROJ = Path.cwd()
SRC = Path("/tmp/oa-framework-files")

with open(SRC / "docs/framework-sync.json", "r", encoding="utf-8") as f:
    manifest = json.load(f)

items = manifest.get("sync") or []
if not items:
    raise SystemExit("清单为空，停止同步")

for item in items:
    rel = item["path"].strip().strip("/")
    mode = item.get("mode", "copy")
    src = SRC / rel
    dst = PROJ / rel
    if not src.exists():
        print(f"[跳过] 来源不存在: {rel}")
        continue

    if src.is_dir():
        dst.mkdir(parents=True, exist_ok=True)
        # 先复制/覆盖来源中的文件
        for root, dirs, files in os.walk(src):
            r = Path(root)
            rel_dir = r.relative_to(src)
            out_dir = dst / rel_dir
            out_dir.mkdir(parents=True, exist_ok=True)
            for d in dirs:
                (out_dir / d).mkdir(parents=True, exist_ok=True)
            for name in files:
                shutil.copy2(r / name, out_dir / name)
        # mirror: 删除目标中来源不存在的文件（仅限该目录内）
        if mode == "mirror":
            for root, dirs, files in os.walk(dst, topdown=False):
                r = Path(root)
                rel_dir = r.relative_to(dst)
                src_dir = src / rel_dir
                for name in files:
                    if not (src_dir / name).exists():
                        (r / name).unlink()
                        print(f"[清理] {dst.relative_to(PROJ) / rel_dir / name}")
                for d in dirs:
                    dp = r / d
                    if not (src_dir / d).exists():
                        shutil.rmtree(dp)
                        print(f"[清理] {dst.relative_to(PROJ) / rel_dir / d}/")
        print(f"[同步:{mode}] {rel}")
    else:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        print(f"[同步:{mode}] {rel}")

print("同步完成")
PY
```

> 若环境无 `python3`，可手工按 `framework-sync.json` 中的条目执行：对每个 `path` 从 `/tmp/oa-framework-files/<path>` 复制到项目同路径；`mode=mirror` 的目录在复制后删除目标目录内来源不存在的文件/子目录（仅该路径范围内）。

### 4. 清理临时文件

```bash
rm -rf /tmp/oa-framework-files /tmp/oa-framework-files.tar.gz
```

### 5. 验证

```bash
# 清单与关键文件存在
test -f docs/framework-sync.json \
  && test -f docs/open-admin/guide.md \
  && test -f .opencode/skills/oa-upgrade-docs/SKILL.md \
  && echo "ok"
# 可选：查看本次变更
git status --short -- docs/framework-sync.json .opencode/skills docs/open-admin
```

## 历史项目引导/自举（本地 skill 过旧或缺失时）

历史项目可能仍使用旧版 `oa-upgrade-docs`（依赖已移除的 `framework-files.zip`）或缺少框架 skill，导致无法自动同步。此时**不要依赖本地旧 skill**，直接在业务项目根目录按目标 tag 执行一次上述「2. 校验并下载解包 → 3. 按清单同步 → 4. 清理 → 5. 验证」流程，即可把 `docs/framework-sync.json` 与最新框架 skill/文档一起同步进来。完成后即可使用新版本 `oa-upgrade-docs` / `oa-upgrade` skill。

最小可复制示例（将 `TAG` 换成目标版本对应 tag）：

```bash
TAG="v3.4.0"   # 或 snapshot-v3.4.0-1
git ls-remote --exit-code --tags https://github.com/jiangood/open-admin.git "refs/tags/$TAG" >/dev/null || exit 1
rm -rf /tmp/oa-framework-files /tmp/oa-framework-files.tar.gz && mkdir -p /tmp/oa-framework-files
curl -fL --retry 3 -o /tmp/oa-framework-files.tar.gz "https://github.com/jiangood/open-admin/archive/refs/tags/$TAG.tar.gz"
tar -xzf /tmp/oa-framework-files.tar.gz -C /tmp/oa-framework-files --strip-components=1
test -f /tmp/oa-framework-files/docs/framework-sync.json || exit 1
# 随后执行第 3 步的 python3 清单同步脚本，再清理临时文件
```

## 验证清单

- [ ] 已换算出正确的目标 tag（正式版 `vX.Y.Z` / 快照版 `snapshot-vX.Y.Z-B`）且 tag 可访问
- [ ] 解包后存在 `docs/framework-sync.json`、`docs/open-admin/guide.md`、`.opencode/skills/oa-upgrade-docs/SKILL.md`
- [ ] 已按清单完成同步（清单中的 `mirror` 项与来源一致，且未影响清单外文件）
- [ ] `docs/framework-sync.json`、框架 skill、`docs/open-admin/` 已更新为目标版本内容

## 故障排查

| 问题 | 处理 |
|------|------|
| tag 不存在 / 404 | 版本号或 build 号错误、tag 未推送或快照已清理；用 `git ls-remote --tags ...` 或 `gh api repos/jiangood/open-admin/tags` 核对 |
| 解包后缺少 `docs/framework-sync.json` | 目标版本早于清单机制（清单曾在根目录或尚未引入）；按 README「历史项目升级」说明使用对应版本的方式同步，或升级到包含清单的版本 |
| `tar`/`curl` 不可用 | 安装对应工具（如 `apt-get install -y curl tar`），或在有工具的环境执行后拷贝结果 |
| `python3` 不可用 | 手工按清单逐项复制；`mirror` 目录需额外清理目标内来源不存在的文件 |
| 业务项目无 pom.xml | 确认在正确的项目根目录执行 |
| 同步后仍是旧行为 | 确认同步的是目标 tag 的内容且执行了清单同步；必要时先跑一次「历史项目引导/自举」 |

## 参考

- 框架仓库: https://github.com/jiangood/open-admin
- 文件来源：`https://github.com/jiangood/open-admin/archive/refs/tags/{TAG}.tar.gz`
- 同步范围：以该 tag 内的 `docs/framework-sync.json` 为准
