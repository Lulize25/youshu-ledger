# 记一笔（youshu-ledger）

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/Lulize25/youshu-ledger)](https://github.com/Lulize25/youshu-ledger/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-9.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)

一个纯离线的安卓记账应用。核心特性：**余额从「启用日期 + 期初余额」起算，早于启用日期的补录流水只进报表、不动余额**。

- 技术栈：Kotlin + Jetpack Compose（Material 3）+ Room（SQLite）+ MVVM + Coroutines/Flow
- 包名：`com.youshu.ledger`；minSdk 28，targetSdk 36
- 无任何网络请求、无账号体系；附件保存在应用私有目录 `filesDir/attachments/`
- 界面语言：中文

## 功能一览

| 模块 | 说明 |
| --- | --- |
| 流水 | 按天分组列表（每天显示当日收支合计）、筛选（账本/账户/分类/类型/时间区间，起止精确到分钟）、备注搜索、点击进入编辑；每项可展开显示「经过这一步之后」各涉及账户的余额 |
| 记一笔 | 支出 / 收入 / 转账，分类树选择（支持搜索与当场新建一级/二级分类）、账户选择、任意业务日期（早于账户启用日期即补录）、交易时间精确到分钟、项目名称、备注、图片附件；支出支持退款 / 报销（到账账户 + 发生时间必填）/ 代付（按人逐条登记：谁、金额、账户、时间、备注）与收回 |
| 账户 | 期初余额 + 启用日期（精确到分钟），支持负余额（信用卡），归档 / 删除，单账户流水；余额可按任意「截至时刻」回看 |
| 账本 | 多账本隔离（预算、账户、流水均按账本），默认账本；分类自 v1.5.1 起全局共享，支持导出 / 导入（JSON） |
| 统计 | 日 / 周 / 月 / 年周期，收支概览、分类饼图、收支趋势柱状图、预算进度、应收总额 |
| 预算 | 月度总预算 + 分类级预算（均可选） |
| 导入导出 | CSV 导出（UTF-8 带 BOM）、ZIP 导出（含附件）、CSV 导入（列映射 + 重复检测）、JSON 全量备份 / 恢复 |
| 小组件 | 4×2 本月支出 + 预算进度 + 一键记一笔；2×2 一键记一笔 |
| 安全 | 启动及后台返回时生物识别（可回退设备锁屏密码 / PIN），设置中可关闭（默认开启） |

## 核心口径（务必理解）

- 账户当前余额 = 期初余额 + Σ（业务日期 ≥ 账户启用日期 的流水影响）
- 补录（业务日期早于账户启用日期的流水）：允许录入、计入历史统计，但**绝不参与余额计算**；修改 / 删除补录同样不动余额
- 退款、报销到账、收回代付：发生即给账户 +金额（退款/报销计入各自指定的到账账户），但不记为收入流水；统计上归属**原支出的业务日期**；余额按「截至时刻」回看时以发生时间过滤
- 支出总额 = 流水金额合计；净支出 = 金额 − 退款 − 报销；个人实际支出 = 金额 − 代付 − 退款 − 报销
- 应收（别人欠我）= Σ(代付金额 − 已收回金额)；结余 = 收入合计 − 净支出

## 构建

需要：JDK 17+、Android SDK（platform-36、build-tools 36.0.0）、Android Studio（可选）。

```bash
# 克隆
git clone https://github.com/Lulize25/youshu-ledger.git
cd youshu-ledger

# 命令行构建（local.properties 中配置 sdk.dir）
./gradlew assembleDebug    # 调试包：约 60 MB，含完整调试符号
./gradlew assembleRelease  # 精简包：约 4 MB，R8 混淆 + 资源精简，正式签名

# 运行单元测试
./gradlew test
```

也可以直接用 Android Studio 打开工程根目录，自动同步后运行 `app` 配置。

### 正式签名（release 包）

release 构建默认读取根目录 `keystore.properties`：

```properties
storeFile=youshu-release.keystore
storePassword=你的密钥库密码
keyAlias=你的别名
keyPassword=你的密钥密码
```

- `keystore.properties` 与 keystore 文件已加入 `.gitignore`，**不要提交到任何仓库**
- keystore 一旦丢失将无法覆盖安装更新（用户只能卸载重装），请自行备份到安全位置
- 没有 keystore 时构建自动退回 debug 签名，不影响编译运行

## 切换里程碑 tag

```bash
git tag -l            # 查看全部 tag
git checkout v0.1.0   # 核心记账可用
git checkout v0.2.0   # 统计与预算完成
git checkout v1.0.0   # 全部验收用例通过
git checkout v1.5.0   # 记一笔大改版（金额校验对话框 / 代付按人登记 / 退款报销到账账户）
git checkout v1.5.2   # 分类全局共享 + 实时刷新 + 分类导出/导入（修复 v1.5.1 保存闪退）
git checkout v1.5.3   # 记一笔时间改左右两栏（日期/时间独立对话框）+ 修复日期选择器差一天
git checkout v1.5.4   # 启用日期改为校准日期（标准时刻余额=期初余额）
git checkout v1.5.5   # 流水可展开事后余额 / 账户与流水小字改为名称余额下方通栏
git checkout v1.5.6   # 全部日期时间入口统一为「左日历选日期 + 右时钟选时刻」
git checkout v1.6.0   # 账本栏置顶放大 / 流水条目重构（时间置顶·项目名·备注·展开全账户余额）/ 记一页重排·金额改系统数字键盘文本框
git checkout v1.6.1   # 账本栏与页面标题合并为同一根顶栏，消除两行之间的大空隙
git checkout v1.6.2   # 顶栏压低（60dp）且内容顶部对齐，账本行与标题紧贴，更省高度
git checkout v1.6.3   # 顶栏只保留账本切换（去掉页面标题）/ 流水汇总条数字与标签同字号对齐
git checkout v1.6.4   # 当前版本：流水条目改版（图标+时间/项目名+金额/账户|分类路径+余额/备注+展开）/ 分类严格两级
```

## 分支模型

- `main`：始终可运行，只接收合并
- `dev`：开发分支，功能按提交拆分后合并入 `main`

## 从 v1.5.0 升级到 v1.5.1 / v1.5.2

覆盖安装即可，数据库自动迁移（v6→v7→v8），**v1.5.1 请直接跳到 v1.5.2**（v1.5.1 有缺陷：保存分类时外键校验失败导致闪退，已在 v1.5.2 修复）。本版起**分类不再按账本隔离，全应用共享**：

- 各账本中重复的分类（同类型 + 同名 + 同父）自动合并为一条，流水与预算中的引用自动重定向，余额与统计不受影响
- categories 表移除 bookId 外键（全局分类不隶属于任何账本，删除账本不会再误删分类）
- 分类管理页实时刷新（新增 / 删除 / 编辑立即生效），新增「导出分类 / 导入分类」（JSON 文件，可跨设备）

## 从 v1.4.x 升级到 v1.5.0

直接覆盖安装即可，数据库自动从 v5 迁移到 v6，**不需要卸载重装，不丢数据**。迁移会做三件事：

1. `transactions` 表新增 `project` 列（项目名称），旧流水为空串
2. 退款 / 报销记录补填两个新列：`accountId`（到账账户，取原流水的支出账户）、`timeMillis`（发生时间，取原记录的录入时间）——余额口径与升级前一致
3. 旧流水上「代付金额 > 0」的自动转成一条代付记录（人名留空，金额、账户、时间沿用流水），之后代付改为按人逐条登记

升级后建议做两件事：

- 有旧代付流水的，进入对应流水把代付人名补上（不影响金额，只影响记录明细）
- 如果平时依赖 ZIP / JSON 备份，建议升级后立刻导出一份新格式备份（旧备份仍可恢复，新备份会带上逐人代付明细）

## 数据库说明

Room 导出 schema 于 `app/schemas/`。结构变更必须提供 Migration（如 v1→v2 账户图标迁移）——**绝不使用破坏性重建**，无迁移路径时升级会直接报错而不是清空数据。预置默认账本与默认分类在首次启动时自动创建；同签名覆盖安装时数据库原样保留。

## 验收用例通过说明

| # | 用例 | 验证方式 | 结论 |
| --- | --- | --- | --- |
| 1 | 期初 500 → 支出 100 → 400 → 收入 200 → 600 | `BalanceCalculatorTest`：`case1 basic expense and income affect balance` | 通过 |
| 2 | 补录 300 不动余额、计入历史统计、再编辑仍不动 | `BalanceCalculatorTest`：`case2 backfill transaction does not affect balance`、`refund on backfill counts but expense does not` | 通过 |
| 3 | 退款 40 → 余额 +40、当日净支出 60 | `BalanceCalculatorTest`：`case3 refund adds to balance immediately` | 通过 |
| 4 | 代付 40 → 个人支出 60 / 应收 40；收回 40 → 余额 +40、应收 0 | `BalanceCalculatorTest`：`case4 advance and recovery` | 通过 |
| 5 | A→B 转账 100（手续费 0）→ A−100、B+100 | `BalanceCalculatorTest`：`case5 transfer between accounts`、`transfer fee only deducted from source account` | 通过 |
| 6 | 删除启用日后流水余额回滚 | `BalanceCalculatorTest`：`case6 deleting transaction rolls back balance`；余额为派生重算（期初 + Σ流水影响 + Σ退款/报销/收回），删除后重算天然回滚 | 通过 |
| 7 | 同一 CSV 二次导入全部按重复跳过、报告数字正确 | 重复检测见 `CsvEngine.import`：同账本内「业务日期+金额+账户+类型」查重，默认跳过并在报告中计数；序列化往返由 `CsvEngineTest`：`export csv has BOM and round-trips through parse` 覆盖 | 通过（代码级） |
| 8 | JSON 备份 → 清数据 → 恢复一致 | `LedgerBackupManager.restore`：清空后按 账本→分类→账户→流水(含退款/报销/附件)→预算 顺序重建，附件重映射本机路径 | 通过（代码级） |
| 9 | 新账本 B 分类为空、可从 A 导入、互不影响 | `CategoryRepository.copyFromBook`（深拷贝生成新 id）；`CategoriesScreen`「从其他账本导入分类」入口 | 通过 |
| 10 | 生物识别：后台切回需验证，关闭开关不再验证 | `MainActivity.onStart` 重新校验 + `SettingsStore.biometricEnabled` 开关；无可用生物特征时自动回退设备凭据 | 通过（代码级） |
| 11 | 小组件本月支出与 APP 一致、按钮直达记一笔 | `WidgetProviders` 监听 `SettingsStore.dataVersion` 自动刷新；`ACTION_QUICK_ADD` 跳转记一笔页 | 通过（代码级） |

单元测试共 25 项（余额口径 15 + CSV 引擎 4 + 统计周期 6），`./gradlew testDebugUnitTest` 全部通过。

> 说明：无模拟器 / 真机环境，用例 7~11 依赖系统组件（Room 数据库、生物识别、桌面小组件）的部分为代码级核对；用例 1~6 的余额口径有完整单元测试覆盖。建议在真机上按上表手动回归一遍。
