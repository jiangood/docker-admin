---
name: oa-sonar-scan
description: Use when scanning the open-admin framework repo (or code it produces) with the local SonarQube — run the scan, fetch results, triage/fix findings one by one with user confirmation, and verify with re-scans. Do not use for configuring SonarQube itself or scanning external projects.
---

# OA Sonar Scan

## Overview

Runs the open-admin repo through the local SonarQube (http://192.168.100.101:9000) with real JaCoCo coverage, then triages and fixes findings (bugs / vulnerabilities / code smells) one at a time, verifying each fix with a re-scan before committing.

## When to Use

- After a batch of changes to the framework backend (`src/main/java`)
- Before a release, to confirm no new Bugs / Vulnerabilities
- When SonarQube reports issues on the `open-admin` project

## Prerequisites

- Local SonarQube running (port 9000, docker-compose under `/ws/sonarqube`)
- Maven + local SonarQube token（`~/.config/open-admin.env` 或环境变量 `SONAR_TOKEN`，或用下面示例中的默认值）
- H2 test DB — no MySQL needed (Repository/Service tests use H2)

## Immediate Execution

**When invoked, begin executing immediately — do NOT ask "What would you like me to do?".** Run the scan first, then present the metrics, then triage findings **one by one**, pausing for the user to confirm each fix (per user's working style).

## 环境变量配置

优先读取框架统一环境变量文件 `~/.config/open-admin.env`（含 `SONAR_TOKEN` / `SONAR_HOST_URL` / `SONAR_PROJECT`）。若该文件存在且已设置相关变量，直接使用，无需再询问用户。

仅当上述变量在 env 文件与当前环境变量中均未设置时，才打印以下提示让用户确认：

```
SonarQube 扫描需要以下环境变量（直接回车使用默认值）：

  SONAR_TOKEN    [默认: squ_156b1e2938c4f5cac460156c4881ff06c6209d5e]
  SONAR_HOST_URL [默认: http://192.168.100.101:9000]
  SONAR_PROJECT  [默认: open-admin]

请输入 SONAR_TOKEN:
```

等待用户输入后继续执行。如果用户直接回车，则使用默认值。

## Workflow

### 1. Run the Scan

```bash
# 优先读取框架统一环境变量文件（含 SONAR_TOKEN / SONAR_HOST_URL / SONAR_PROJECT）
[ -f "$HOME/.config/open-admin.env" ] && . "$HOME/.config/open-admin.env"
export TOKEN="${SONAR_TOKEN:-squ_156b1e2938c4f5cac460156c4881ff06c6209d5e}"
export HOST="${SONAR_HOST_URL:-http://192.168.100.101:9000}"
export PROJECT="${SONAR_PROJECT:-open-admin}"

mvn -B -ntp verify sonar:sonar \
  -Dsonar.login="$TOKEN" \
  -Dsonar.host.url="$HOST" \
  -Dsonar.projectKey="$PROJECT" \
  -Dsonar.projectName="$PROJECT" \
  -Dsonar.sources=src/main/java,web/src \
  -Dsonar.java.binaries=target/classes \
  -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \
  -Dsonar.exclusions='**/docs/**,**/test/**,web/dist/**,web/coverage/**,web/playwright-report/**'
```

业务项目按需替换 `PROJECT` 和 `sources` 参数。

### 2. Fetch Metrics

等待 ~10s（服务器端处理），然后用 curl 查询指标：

```bash
export AUTH=$(echo -n "$TOKEN:" | base64)

# 总指标
curl -s -H "Authorization: Basic $AUTH" \
  "$HOST/api/measures/search?projectKeys=$PROJECT&metricKeys=bugs,vulnerabilities,code_smells,coverage,duplicated_lines_density&ps=20" \
  | jq -r '.measures[] | "\(.metric) = \(.value)"'

# New Code 指标
curl -s -H "Authorization: Basic $AUTH" \
  "$HOST/api/measures/component?component=$PROJECT&metricKeys=new_violations,new_bugs,new_vulnerabilities,new_code_smells,new_lines,new_coverage" \
  | jq -r '.component.measures[] | "\(.metric) = \(.period.value // "")"'

# Quality Gate 状态
curl -s -H "Authorization: Basic $AUTH" \
  "$HOST/api/qualitygates/project_status?projectKey=$PROJECT" \
  | jq -r '.projectStatus | "status: \(.status)\n\(.conditions[] | " - \(.metricKey) actual=\(.actualValue) error=\(.errorThreshold) | \(.status)")"'
```

### 3. List Unresolved Issues

Always filter `resolved=false` — default search includes already-fixed issues:

```bash
# 未解决 BUG
curl -s -H "Authorization: Basic $AUTH" \
  "$HOST/api/issues/search?projectKeys=$PROJECT&types=BUG&resolved=false&ps=200" \
  | jq -r '"total \(.total)\n\(.issues[] | " - \(.rule) | \(.component | split(":")[-1]) : \(.line)")"'

# 未解决 VULNERABILITY
curl -s -H "Authorization: Basic $AUTH" \
  "$HOST/api/issues/search?projectKeys=$PROJECT&types=VULNERABILITY&resolved=false&ps=200" \
  | jq -r '"total \(.total)\n\(.issues[] | " - \(.rule) | \(.component | split(":")[-1]) : \(.line)")"'
```

其他查询：

```bash
# 指定规则的未解决问题
RULE="typescript:S6756"
curl -s -H "Authorization: Basic $AUTH" \
  "$HOST/api/issues/search?projectKeys=$PROJECT&rules=$(urlencode $RULE)&resolved=false&ps=200"

# 定位新引入的问题（createdAfter 之后）
AFTER="2026-08-15T07:00:00%2B0000"
curl -s -H "Authorization: Basic $AUTH" \
  "$HOST/api/issues/search?projectKeys=$PROJECT&createdAfter=$AFTER&resolved=false&ps=200"

# 指标历史趋势
curl -s -H "Authorization: Basic $AUTH" \
  "$HOST/api/measures/search_history?component=$PROJECT&metrics=bugs,code_smells,coverage&ps=30"

# 分析历史（找 leak period 起点）
curl -s -H "Authorization: Basic $AUTH" \
  "$HOST/api/project_analyses/search?project=$PROJECT&ps=10"
```

### 4. Triage & Fix One By One

**Follow the user's confirm-per-fix workflow**: for each finding, show the exact code + proposed diff, let the user confirm, then edit → test → commit → re-scan before moving to the next.

Guidance by finding type:

| Rule | Nature | Approach |
|---|---|---|
| S2095 (resource leak) | real | `try-with-resources` / `finally` |
| S2259 (NPE) | often real | null check + fail-fast; if it's a `.orElse(...)` false positive, use enhanced-for loop or `Objects.requireNonNull` — **not** `if (x == null) continue;` (triggers S135 in loops) |
| S5122 / S4507 | real | remove wildcard CORS header / replace `printStackTrace` with SLF4J |
| S6218 (record + array) | real | override `equals/hashCode/toString` with `Arrays.*`; prefer record pattern `instanceof PayloadFile(String a, byte[] b)` |
| S3077 (volatile lazy) | real | static holder singleton |
| S2184 (int overflow) | mostly theoretical | cast to `long`; explain honestly when it can't actually overflow |
| S3752 (no HTTP method) | mechanical | `@GetMapping`/`@PostMapping`; check the **frontend** call site method first |
| S4684 (entity as request body) | real, large | introduce request DTO whitelist, `BeanUtil.copyProperties(req, Entity.class)` in controller; services unchanged |
| S2077 / S4502 | confirmed-safe design | add `// NOSONAR` with the security rationale (identifier whitelist / SPA-CSRF-immune) |
| S1872 (test) | mechanical | `X.isAssignableFrom(cls)` |
| S6756 (TS/JS setState) | real | functional `setState(prev => ...)`; when `onChange` depends on the new value, compute in the updater + setState's 2nd callback (state is applied by then) — don't mutate `this.state` directly |
| S6819 (TS/JS a11y) | real | don't fake `role="button"` on `<div>/<span>/<a>/<img>` — use a real `<button type="button">` (native Enter/Space); reset default button styles (`background:none;border:none;padding:0;font:inherit;cursor:pointer`) |
| S1082 (TS/JS a11y) | real | clickable non-interactive elements need a keyboard listener — **prefer the native `<button>`** (satisfies both S1082 and S6819); avoid `role="button"+tabIndex+onKeyDown` which just moves the finding to S6819 |
| S1848 (unused `new`) | often FP | if it's a constructor-side-effect API (e.g. compressorjs), add `// NOSONAR` **on the same line** as the finding |
| S4335 (intersection = any) | real | `X & any` always collapses to `any` — remove the `& any`, keep the real type param |
| S2245 (PRNG) | real | replace `Math.random()` with `crypto.getRandomValues()` (browser + Node 19+ global) |
| S6544 (async lifecycle) | real | React lifecycle methods must return `void` — extract async work into a named method / Promise chain, don't make `componentDidMount` async |
| S2160 (equals) | mechanical | add Lombok `@EqualsAndHashCode(callSuper = true)` (matches parent `BaseEntity`) |

Test commands after fixes:

```bash
mvn test -Dtest='<AffectedTestClass>'           # targeted
mvn test -Dtest='!*RepositoryTest,!*ServiceTest' # fast unit set
mvn test                                        # full suite before committing big changes
```

Commit style: `fix(excel): ... (sonar S2095)`

### 5. Re-scan to Verify

重新执行 Step 1 的 mvn 命令，然后用 Step 2/3 的 curl 命令确认 **unresolved** 计数。迭代直到 `Bugs=0`、`Vulnerabilities=0`、`new_violations=0`。

## Quality Gate Notes

- The gate's `new_coverage` (threshold 80%) will almost always stay ERROR for this framework — that's expected, not a code problem; user decides whether to relax it in SonarQube config.
- `new_violations` counts only issues introduced since the leak period; clean it up to 0.

## Common Mistakes

- Forgetting `resolved=false` → counts include already-fixed issues
- Trusting `api/issues/search` totals while server is still indexing (wait ~10s; if counts disagree with `api/measures`, re-query)
- **Using `api/measures/search` for new-code metrics** → new_* returns `undefined`; use `api/measures/component`
- **Trusting `sinceLeakPeriod=true`** → does NOT work on SonarQube 26.8 (returns all issues); use `createdAfter=<leak period start analysis time>` instead
- **Putting `// NOSONAR` on the wrong line** → must be on the same line as the finding (S1848 case)
- **"Fixing" S1082 with `role="button"`** → triggers S6819 (new violation). Use native `<button>` from the start
- Fixing a "false positive" by adding a `continue` (S135) or dead null-check — use `Objects.requireNonNull` / enhanced-for / `NOSONAR` instead
- Changing `@RequestMapping` to `@GetMapping` without verifying the frontend actually calls GET
- Removing a `throws Exception` without checking the service methods it calls can still throw checked exceptions
- Running `sonar:sonar` without `verify` → no JaCoCo report → coverage stays 0

## Verifying a Fix

- Frontend: verify with `cd web && npm run build`
- Backend: `mvn clean compile` or targeted test
- After each fix batch: re-scan, then confirm **both** overall counts (bugs/vulns) AND `new_violations` went down / stayed 0
