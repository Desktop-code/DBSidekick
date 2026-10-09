package com.dbsidekick.text2sql;

import com.dbsidekick.ai.milvus.SchemaHit;
import com.dbsidekick.datasource.DbVersionInfo;
import com.dbsidekick.sqlguard.SqlRuntimeConfig;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Text2SQL Prompt 组装。
 */
@Component
public class PromptBuilder {

    private final SqlRuntimeConfig sqlRuntimeConfig;

    public PromptBuilder(SqlRuntimeConfig sqlRuntimeConfig) {
        this.sqlRuntimeConfig = sqlRuntimeConfig;
    }

    public String buildSystemPrompt(String dialect) {
        return buildSystemPrompt(dialect, null);
    }

    public String buildSystemPrompt(String dialect, DbVersionInfo versionInfo) {
        String d = StringUtils.hasText(dialect) ? dialect.trim() : "MySQL";
        String limitRule = sqlRuntimeConfig.limitEnabled()
                ? "5. 必须包含 LIMIT 子句，最多 " + sqlRuntimeConfig.defaultLimit()
                : "5. 可不强制 LIMIT；若写出 LIMIT，请控制在合理范围";
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一个专业的 SQL 生成助手。根据用户问题和你检索到的表结构，生成一条可执行的 SQL。

                硬性规则：
                1. 只输出 SQL，不要任何解释、不要 markdown 代码块
                2. 只允许 SELECT 查询，禁止 INSERT/UPDATE/DELETE/DROP/ALTER
                3. 必须使用检索到的真实表名和字段名，不要臆造
                4. 生成的 SQL 必须使用 %s 方言
                %s
                6. 时间范围如果用户说"最近N天"，用对应方言的时间函数
                7. 若有对话历史，当前问题是对上一轮的追问或修正，请结合历史理解意图
                8. 参考示例仅供参考 SQL 风格和关联方式，不要直接套用，字段和条件要根据用户的实际问题调整
                9. FROM / JOIN 后的子查询必须有别名，例如 FROM (SELECT ...) AS t
                10. SQL 正上方必须有一行中文注释，说明查询意图，例如：
                    -- 统计最近7天各门店销售额
                    SELECT ...
                11. 【重要】你只能使用下方【检索到的相关表结构】中出现的表名和字段名。
                    如果某个表或字段不存在于该列表中，禁止引用，即使你认为它应该存在。
                    宁可生成简单 SQL，也不要编造不存在的表或字段。
                12. 外层 SELECT 引用子查询别名时，只能使用该子查询 SELECT 列表中实际选出的列，
                    禁止引用未投影的列（例如子查询没选 shop_id，外层就不能写 a.shop_id）。
                """.formatted(d, limitRule).trim());
        if (versionInfo != null) {
            sb.append("\n\n").append(versionInfo.capabilitySummary());
        }
        return sb.toString();
    }

    public String buildScriptSystemPrompt(DbVersionInfo versionInfo) {
        String versionBlock = versionInfo == null
                ? "当前数据库信息：未知"
                : versionInfo.capabilitySummary();
        String limitRule = sqlRuntimeConfig.limitEnabled()
                ? "5. 每条 SELECT 最多 LIMIT " + sqlRuntimeConfig.defaultLimit()
                : "5. SELECT 可不强制 LIMIT；若写出请控制在合理范围";
        int maxStmt = sqlRuntimeConfig.maxStatements();
        return """
                你是一个专业的 SQL 脚本生成助手。用户的问题可能需要多步骤处理（比如先建临时表存中间结果，再关联查询）。
                你可以生成多条 SQL 语句，用分号分隔。

                硬性规则：
                1. 只允许以下语句类型：
                   - SELECT 查询
                   - CREATE TEMPORARY TABLE tmp_xxx AS SELECT ... 或 CREATE TEMPORARY TABLE tmp_xxx (...)
                   - DROP TEMPORARY TABLE IF EXISTS tmp_xxx
                   - SET @变量 = ...
                2. 禁止 CREATE TABLE、DROP TABLE（非 TEMPORARY）、UPDATE、DELETE、ALTER、TRUNCATE
                3. 临时表名必须以 tmp_ 或 temp_ 开头（例如 tmp_orders、tmp_users）
                4. **最后一条语句必须是 SELECT**（返回最终结果）
                %s
                6. 语句总数不超过 %d 条（含 DROP/CREATE/SET/SELECT）；优先合并步骤，能少则少
                7. 只输出 SQL 脚本，不要 markdown 代码块，不要解释
                8. **MySQL 致命限制（违反必失败）**：同一条 SQL 中，每张临时表最多引用 1 次。
                   禁止：FROM tmp_x JOIN tmp_x / WHERE IN (SELECT … FROM tmp_x) 且外层也用了 tmp_x。
                   正确做法：
                   -- 需要两次用 tmp_sales 时先复制
                   CREATE TEMPORARY TABLE tmp_sales_2 AS SELECT * FROM tmp_sales;
                   -- 最终查询分别引用
                   SELECT … FROM tmp_sales a JOIN tmp_sales_2 b ON …;
                   MySQL 不允许在**同一条 SQL 语句中两次打开同一临时表**。
                   或者拆成多条语句分步处理。
                9. **派生表必须起别名**：FROM (SELECT ...) AS t / JOIN (SELECT ...) AS j，
                   缺少别名会导致 near 'LIMIT' 语法错误。
                10. 不要为每一步都写 DROP；同一脚本内临时表名尽量不冲突即可，减少无用语句。
                11. **每条语句正上方必须有一行中文注释**，说明该步作用，例如：
                    -- 汇总订单金额到临时表
                    CREATE TEMPORARY TABLE tmp_order_sum AS SELECT ...;
                    -- 最终查询：按客户关联汇总结果
                    SELECT ... FROM tmp_order_sum ...;
                12. 【重要】你只能使用下方【检索到的相关表结构】中出现的业务表名和字段名；
                    临时表（tmp_/temp_）除外。禁止引用未检索到的业务表或臆造字段。
                13. 外层查询只能使用子查询/临时表中已选出的列；UNION 整段只在最外层写一次 LIMIT。

                %s

                如果问题简单（单条 SELECT 就能完成），直接返回单条 SELECT（同样要有上方注释），不要为了复杂而复杂。
                """.formatted(limitRule, maxStmt, versionBlock).trim();
    }

    public String buildUserPrompt(String question, List<SchemaHit> schemaHits) {
        return buildUserPrompt(question, schemaHits, null, null, false);
    }

    public String buildUserPrompt(String question, List<SchemaHit> schemaHits, String historyText) {
        return buildUserPrompt(question, schemaHits, historyText, null, false);
    }

    public String buildUserPrompt(String question, List<SchemaHit> schemaHits,
                                  String historyText, List<FewShotExample> fewShots) {
        return buildUserPrompt(question, schemaHits, historyText, fewShots, false);
    }

    public String buildUserPrompt(String question, List<SchemaHit> schemaHits,
                                  String historyText, List<FewShotExample> fewShots,
                                  boolean scriptMode) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(historyText)) {
            sb.append("对话历史：\n");
            sb.append(historyText.trim());
            sb.append("\n---\n\n");
            sb.append("当前问题：").append(question == null ? "" : question.trim()).append("\n\n");
        } else {
            sb.append("用户问题：").append(question == null ? "" : question.trim()).append("\n\n");
        }
        sb.append("检索到的相关表结构（**以下是你唯一可以使用的表和字段**）：\n");
        sb.append("---\n");
        if (schemaHits != null) {
            for (int i = 0; i < schemaHits.size(); i++) {
                SchemaHit hit = schemaHits.get(i);
                if (hit == null) {
                    continue;
                }
                if (i > 0) {
                    sb.append("---\n");
                }
                String content = hit.getContent();
                sb.append(StringUtils.hasText(content) ? content.trim() : ("表名：" + hit.getTableName()));
                sb.append('\n');
            }
        }
        sb.append("---\n\n");

        if (fewShots != null && !fewShots.isEmpty()) {
            sb.append("参考示例（类似问题的正确 SQL）：\n");
            for (FewShotExample ex : fewShots) {
                if (ex == null) {
                    continue;
                }
                String q = clip(ex.getQuestion(), 80);
                String sql = clip(ex.getSqlText(), 200);
                sb.append("问题：").append(q).append('\n');
                sb.append("SQL：").append(sql).append('\n');
                sb.append('\n');
            }
            sb.append("---\n\n");
        }

        if (scriptMode) {
            sb.append("这是一个复杂问题，你可以使用临时表和多步骤查询。\n\n");
        }
        sb.append("请生成 SQL：");
        return sb.toString();
    }

    public String buildRetryUserPrompt(String previousSql, String error) {
        String err = error == null ? "" : error.trim();
        String hint = "";
        String lower = err.toLowerCase();
        if (lower.contains("can't reopen table") || lower.contains("cannot reopen table")
                || err.contains("不能重新打开表") || err.contains("无法重新打开")
                || err.contains("不能两次打开同一张临时表") || err.contains("复制临时表后再关联")
                || err.contains("Can't reopen") || err.contains("tmp_") && err.contains("×")) {
            hint = """

                这是 MySQL 临时表限制：同一条语句里不能两次打开同一张临时表。
                错误示例：
                  SELECT * FROM tmp_a a JOIN tmp_a b ON ...
                  SELECT * FROM tmp_a WHERE id IN (SELECT id FROM tmp_a)
                正确改法（必须复制后再关联）：
                  CREATE TEMPORARY TABLE tmp_a2 AS SELECT * FROM tmp_a;
                  SELECT * FROM tmp_a a JOIN tmp_a2 b ON ...
                规则：
                1) 同一条 SELECT/CTAS 里，每张临时表最多出现一次；
                2) 需要两次使用时，先复制一份 tmp_xxx_2，再分别引用；
                3) 临时表名仍须以 tmp_ / temp_ 开头；
                4) 复制语句单独成行，不要写在最终 SELECT 里。
                """;
        } else if (lower.contains("unknown column") || lower.contains("doesn't exist")
                || err.contains("Unknown column")) {
            hint = """

                字段不存在。请严格对照【检索到的相关表结构】中的列名改写：
                1) 不要臆造 shop_id 等未出现在该表字段列表中的列；
                2) 若业务需要店铺信息，请 JOIN 真正包含该字段的表；
                3) 子查询外层只能引用子查询 SELECT 列表里有的列。
                """;
        } else if (lower.contains("near 'limit") || lower.contains("near \"limit")
                || err.contains("near 'LIMIT") || err.contains("LIMIT 100 LIMIT")) {
            hint = """

                常见原因：
                1) FROM / JOIN 后面的子查询（派生表）缺少别名；
                2) 出现重复 LIMIT（如 LIMIT 100 LIMIT 100），整段 SQL 只能有一个最外层 LIMIT。
                正确示例：SELECT * FROM (SELECT 1) AS t LIMIT 100
                UNION 写法：(... ) UNION ALL (... ) LIMIT 100  —— 不要在每个分支再写 LIMIT。
                """;
                } else if (err.contains("语句数量超过限制") || lower.contains("too many statements")) {
            hint = """

                脚本语句数超限。请压缩步骤后重写：
                1) 合并可一次完成的 CREATE TEMPORARY TABLE AS SELECT；
                2) 去掉多余的 DROP TEMPORARY TABLE；
                3) 能单条 SELECT 完成则不要拆临时表；
                4) 总语句数必须 ≤ 20，最后一条仍须是 SELECT。
                """;
        } else if (err.contains("未检索到的表") || err.contains("引用了未检索")) {
            hint = """

                SQL 引用了检索结果之外的表。请只使用【检索到的相关表结构】中列出的表名，
                删除或替换不存在的表；临时表名必须以 tmp_ / temp_ 开头。
                """;
        }
        return """
                你上一次生成的 SQL 执行报错，错误信息如下：
                %s
                %s
                上一次 SQL：
                %s

                请修正后重新生成，只输出 SQL；每条语句上方仍须带中文注释。
                """.formatted(err, hint, previousSql == null ? "" : previousSql.trim()).trim();
    }

    private static String clip(String s, int max) {
        if (!StringUtils.hasText(s)) {
            return "";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
