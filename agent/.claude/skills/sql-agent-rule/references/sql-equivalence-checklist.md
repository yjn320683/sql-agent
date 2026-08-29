# SQL 语义等价检查清单

本文件只在 `/sql优化` 产生替代 SQL 前读取。任一相关项无法确认时，不要把改写包装成可直接替换版本。

## 结果契约

先明确并保持这些不变量：

- 输出列数量、名称、顺序、类型和别名。
- 结果粒度、行数语义和重复行语义。
- 过滤范围、时间边界、时区和参数含义。
- NULL、空字符串、默认值和三值逻辑。
- join type、join key 和未匹配行保留规则。
- 聚合层级、distinct 范围、having 条件。
- 窗口 partition、order、frame 和并列行处理。
- 全局排序、limit/TopN 和结果稳定性。
- 写入目标、列映射、覆盖范围和分区写入语义。

## 必查项

### 投影

- 是否保留所有原始输出列及顺序。
- `select *` 展开是否基于完整元数据，是否包含多表同名字段。
- 表达式别名和类型是否保持一致。

### 过滤与 NULL

- 谓词下推是否跨越 outer join、聚合、窗口或 limit。
- `not in`、`not exists`、anti join 在 NULL 存在时是否等价。
- `coalesce`、`nvl`、`is null` 改写是否改变空值结果。
- 时间条件的开闭区间是否保持，例如 `< next_day` 与 `<= end_of_day`。

### Join 与重复行

- join type 是否不变。
- join key 两侧类型转换是否可能截断、溢出或解析失败。
- 输入去重或预聚合是否会删除业务有效重复。
- 过滤条件仍位于正确的 `ON` 或 `WHERE` 位置。
- 多表 join 重排是否影响 outer/semi/anti join 语义。

### 聚合

- group by key 与原结果粒度一致。
- 预聚合是否保留后续 join 所需字段和重复计数。
- `count(*)`, `count(col)`, `count(distinct col)` 没有互换。
- distinct 的作用域和 NULL 处理没有变化。

### 窗口、排序与 TopN

- partition by、order by、ASC/DESC、NULLS FIRST/LAST 和 window frame 保持一致。
- TopN 下推不会提前丢弃后续 join/filter 所需行。
- 全局 `order by` 未被不等价的局部排序替换。
- limit 与排序的先后关系保持一致。

### 集合、子查询与 CTE

- `union` 与 `union all` 的重复行语义明确。
- 相关子查询改 join 后，不会因右侧多行产生放大。
- CTE 合并或拆分没有改变随机函数、当前时间或其它非确定表达式的求值次数。
- `exists` 只关心存在性时，没有意外投影右表字段。

### 方言与写入

- 函数、标识符引用、隐式类型转换和日期语法适用于当前方言。
- insert 列顺序与目标表列映射保持一致。
- `insert overwrite`/`insert into`、静态/动态分区语义没有变化。
- 不引入未经确认的引擎 hint、session 参数或版本专属语法。

## 输出判定

- 全部相关项可确认：可以给“可直接替换”的优化 SQL。
- 只有局部项可确认：只改写该局部，并明确其余部分保持原样。
- 关键项无法确认：给诊断和改写骨架，调用 `AskUserQuestion` 或列入“待验证”。
- 改写会改变语义但可能更快：只能作为“可选非等价方案”，明确差异并要求用户确认。
