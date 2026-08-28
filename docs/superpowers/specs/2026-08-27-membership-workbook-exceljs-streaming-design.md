# 会员测试工作簿 ExcelJS 流式导出设计

## 1. 背景与目标

现有导出器使用内存型工作簿模型构建约 270 万个单元格。四张 40,000 行大表写完后，最终扫描、渲染或 XLSX 序列化会同时保留原工作簿和导出中间对象；Node.js 堆上限从 4 GB 提升到 8 GB 后仍然溢出。继续提高堆上限、增加 GC 或降低写入速度都不能消除最终峰值。

本设计将最终工作簿改为 ExcelJS 的流式 `WorkbookWriter`，仍生成一个完整 `.xlsx`，并保留现有只读数据库提取、业务一致性校验、Base64URL 规则、安全临时文件和原子覆盖行为。

## 2. 依赖边界

- 在 `loadtest` 目录新增独立 `package.json` 和锁文件，只服务于压测数据导出工具。
- 使用工作区提供的 pnpm 安装 ExcelJS，并将解析到的注册表版本精确固定，不使用宽松版本范围。
- `node_modules` 不提交，不修改 Codex 自带运行时依赖目录，也不修改 Java 模块 POM。
- PowerShell 继续使用工作区提供的 Node.js 可执行文件，并验证本地 ExcelJS 依赖存在。
- PostgreSQL、Redis、RabbitMQ 和业务应用依赖均不因本次调整而改变。

## 3. 流式写入架构

`Build-MembershipTestWorkbook.mjs` 保留严格的 Hybrid ID、正数 BIGINT 和规范 Base64URL 编解码校验，但不再创建 artifact-tool 内存工作簿。

构建流程固定为：

1. 读取小型 metadata 和 metrics 文件，验证八组顺序、Suite 策略和数据库汇总门禁。
2. 使用流式 CSV 解析器逐条读取一致性明细，只做逐行验证，不保存到数组，也不重复写入一致性汇总表。
3. 创建 `ExcelJS.stream.xlsx.WorkbookWriter`，设置 `useSharedStrings=false`，避免为大量高基数字符串建立全局共享字符串字典。
4. 依次创建八张工作表；大表每读取一条 CSV 记录，立即完成字段校验、ID 转换、单元格类型映射和 `row.commit()`。
5. 每张表写完后验证实际行数，设置筛选、冻结窗格、列宽、文本格式和隐藏审计列，然后执行 `worksheet.commit()`。
6. 所有工作表完成后执行 `workbook.commit()`，将 ZIP 流完整关闭后才发布 staging 文件。

任何时刻只保留 CSV 解析缓冲区、当前记录和 ExcelJS 当前行所需对象。已提交的行不能再修改，因此标题、列定义、冻结窗格、筛选范围、隐藏列和公式必须在提交相关工作表前确定。

## 4. 工作簿兼容性与结构

工作簿仍包含以下固定顺序的八张表：

1. `测试总览`
2. `ID映射`
3. `membership_order`
4. `membership_payment_callback`
5. `user_membership_quota`
6. `区段证据`
7. `一致性校验`
8. `字段说明`

大表保留表头筛选和冻结窗格，但不创建会迫使数据重新常驻内存的 ExcelJS Table 数据集合。Excel 自动筛选提供等价的筛选操作。所有 ID 列使用文本类型和 `@` 格式，原始 BYTEA/BIGINT 审计列保持隐藏。

总览与一致性公式写入公式文本和可信缓存结果，同时启用打开时完整重算。由于 ExcelJS 不负责计算公式，缓存结果只来自已验证的 metadata/metrics，不能覆盖或弱化数据库门禁。

## 5. 低内存验证

成品验证不使用普通 Workbook 重新整体加载，而使用 ExcelJS 流式 `WorkbookReader`：

- 校验八张工作表的名称和顺序。
- 校验 ID 映射和三张业务表各有精确的 40,000 条数据；一致性明细流也必须验证 40,000 条。
- 校验首行表头、首条数据、末条数据和所有关键 ID 单元格类型。
- 校验总览与一致性公式文本不存在错误标记，并与固定公式模板一致。
- 校验隐藏审计列、筛选范围、冻结窗格和关键数字格式对应的 OpenXML/ExcelJS 属性。
- 验证文件必须能够完整流式重开到 EOF，ZIP 或工作表 XML 损坏时停止覆盖。

视觉检查使用构建过程中保留的每张表顶部最多 30 行小样本生成轻量预览；预览不读取或复制 40,000 行成品。样本与正式工作表共用同一列定义和样式配置，以检查标题、列宽、颜色、文本格式和可读性。

## 6. 安全覆盖与失败处理

- PostgreSQL 提取仍在 `REPEATABLE READ, READ ONLY` 快照内完成。
- 所有 CSV、staging XLSX、预览和验证结果位于 GUID 临时目录或目标目录的唯一 staging 文件。
- ExcelJS、CSV、公式、ZIP、行数或样式验证任一失败时，删除 staging 和临时目录，原工作簿保持不变。
- 只有低内存验证全部通过后，PowerShell 才重新检查目标文件锁，并在同一磁盘执行原子替换。
- `Snapshot` 模式继续允许 Suite FAIL 快照，但总览必须显示“完全测试完成=否”；`RequireSuitePass` 继续拒绝 Suite FAIL。

## 7. 测试策略

先添加失败测试，再实现：

- 依赖清单精确固定 ExcelJS，且导出器只从 `loadtest` 本地依赖解析。
- 构建器使用 `WorkbookWriter`、`useSharedStrings=false`、`row.commit()`、`worksheet.commit()` 和 `workbook.commit()`。
- 构建器禁止 artifact-tool 内存工作簿导入/导出路径。
- 小型八组 fixture 生成单个 XLSX，并由流式 Reader 验证八张表、行数、公式、文本 ID、隐藏列和末行。
- CSV 引号、逗号、双引号、CRLF 和空字段能够跨流缓冲区正确解析。
- Hybrid ID 与 BIGINT Base64URL 继续通过已有规范测试，非法输入继续拒绝。
- 构建失败、验证失败、文件锁定和 Suite 策略失败均不得覆盖原文件。
- 正式 40,000 行运行记录每张表已提交行数、当前 RSS 和耗时，用于确认内存不再随已提交行线性增长。

## 8. 完成标准

- 一个完整的 `test 5Kx8.xlsx` 被原子覆盖生成。
- 八张工作表顺序正确，三张业务表和 ID 映射各为 40,000 条。
- ID、原始审计列、公式、Suite FAIL 快照结论和数据库一致性结果满足原计划。
- 流式构建和流式重开验证均成功，过程中不再依赖提高 Node 堆上限来完成。
- 不写入 PostgreSQL，不连接 Redis 或 RabbitMQ，不修改 Java、Mapper 或业务表结构。
