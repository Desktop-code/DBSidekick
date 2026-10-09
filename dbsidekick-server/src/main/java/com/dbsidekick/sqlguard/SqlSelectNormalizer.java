package com.dbsidekick.sqlguard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.util.StringUtils;

/**
 * SELECT 规范化：派生表补别名、可选强制 LIMIT。
 */
final class SqlSelectNormalizer {

    /**
     * FROM/JOIN 后的 (subquery) 若紧跟 LIMIT/JOIN/WHERE/逗号等且无别名，则插入 AS _dN。
     * 避免误伤 IN (1,2,3) LIMIT：只匹配 FROM|JOIN 后的派生表。
     */
    private static final Pattern MISSING_DERIVED_ALIAS = Pattern.compile(
            "(?i)(\\b(?:FROM|JOIN)\\s*)\\("
                    + "((?:[^()]|\\([^()]*\\))*)"
                    + "\\)"
                    + "(?!\\s*(?:AS\\s+)?[`\"\\[]?[A-Za-z_][\\w$]*[`\"\\]]?\\b)"
                    + "(\\s*)(?=(?:LIMIT|JOIN|ON|WHERE|GROUP\\s+BY|ORDER\\s+BY|HAVING|UNION|,|;|$))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private SqlSelectNormalizer() {
    }

    /**
     * @param skipLimit     true 表示不强制 LIMIT（如 CTAS 内层）
     * @param limitEnabled  是否启用强制 LIMIT
     * @param defaultLimit  默认 LIMIT
     * @param maxLimitCap   用户自带 LIMIT 的上限
     */
    static String normalize(Select select, boolean skipLimit,
                            boolean limitEnabled, long defaultLimit, long maxLimitCap) {
        if (select == null) {
            throw new IllegalArgumentException("SELECT 不能为空");
        }
        ensureDerivedTableAliases(select);
        if (!skipLimit && limitEnabled) {
            applyLimit(select, defaultLimit, maxLimitCap);
        }
        return collapseDuplicateLimits(fixMissingDerivedAliases(select.toString()));
    }

    static void ensureDerivedTableAliases(Select select) {
        if (select == null) {
            return;
        }
        if (select.getWithItemsList() != null) {
            for (WithItem w : select.getWithItemsList()) {
                if (w != null && w.getSelect() != null) {
                    ensureDerivedTableAliases(w.getSelect());
                }
            }
        }
        if (select instanceof PlainSelect plain) {
            ensureFromAlias(plain.getFromItem(), "t");
            if (plain.getJoins() != null) {
                int i = 0;
                for (Join join : plain.getJoins()) {
                    if (join != null) {
                        ensureFromAlias(join.getRightItem(), "j" + (i++));
                    }
                }
            }
            return;
        }
        if (select instanceof ParenthesedSelect ps) {
            if (ps.getSelect() != null) {
                ensureDerivedTableAliases(ps.getSelect());
            }
            return;
        }
        if (select instanceof SetOperationList setOp) {
            if (setOp.getSelects() != null) {
                for (Select s : setOp.getSelects()) {
                    ensureDerivedTableAliases(s);
                }
            }
        }
    }

    private static void ensureFromAlias(FromItem item, String fallback) {
        if (item == null) {
            return;
        }
        // 派生表 / 子查询：ParenthesedSelect；不要给 Table 起强制别名
        if (item instanceof ParenthesedSelect) {
            if (item.getAlias() == null || !StringUtils.hasText(item.getAlias().getName())) {
                item.setAlias(new Alias(fallback == null ? "t" : fallback, true));
            }
            ParenthesedSelect ps = (ParenthesedSelect) item;
            if (ps.getSelect() != null) {
                ensureDerivedTableAliases(ps.getSelect());
            }
            return;
        }
        // 少数版本可能把子查询建成其它 Select 子类挂在 FromItem 上
        if (item instanceof Select nested && !(item instanceof PlainSelect)) {
            if (item.getAlias() == null || !StringUtils.hasText(item.getAlias().getName())) {
                item.setAlias(new Alias(fallback == null ? "t" : fallback, true));
            }
            ensureDerivedTableAliases(nested);
        }
    }

    /**
     * 字符串兜底：修复 AST 漏网的 {@code FROM (SELECT ...) LIMIT}。
     */
    static String fixMissingDerivedAliases(String sql) {
        if (!StringUtils.hasText(sql)) {
            return sql;
        }
        AtomicInteger seq = new AtomicInteger(0);
        String current = sql;
        // 多层嵌套时多轮替换
        for (int round = 0; round < 8; round++) {
            Matcher m = MISSING_DERIVED_ALIAS.matcher(current);
            StringBuffer sb = new StringBuffer();
            boolean found = false;
            while (m.find()) {
                found = true;
                String alias = "_d" + seq.incrementAndGet();
                m.appendReplacement(sb,
                        Matcher.quoteReplacement(m.group(1) + "(" + m.group(2) + ") AS " + alias + m.group(3)));
            }
            m.appendTail(sb);
            current = sb.toString();
            if (!found) {
                break;
            }
        }
        return current;
    }

    static void applyLimit(Select select, long defaultLimit, long maxLimitCap) {
        if (select instanceof SetOperationList setOp) {
            // 子 SELECT 上若已有 LIMIT，再给 SetOperation 加 LIMIT 会序列化成「LIMIT 100 LIMIT 100」
            if (setOp.getSelects() != null) {
                for (Select child : setOp.getSelects()) {
                    clearLimit(child);
                }
            }
            setOp.setLimit(normalizeLimit(setOp.getLimit(), defaultLimit, maxLimitCap));
            return;
        }
        if (select instanceof ParenthesedSelect ps) {
            if (ps.getSelect() != null) {
                applyLimit(ps.getSelect(), defaultLimit, maxLimitCap);
            }
            return;
        }
        select.setLimit(normalizeLimit(select.getLimit(), defaultLimit, maxLimitCap));
    }

    /** 清除 LIMIT，避免 UNION/嵌套时重复输出。 */
    static void clearLimit(Select select) {
        if (select == null) {
            return;
        }
        if (select instanceof SetOperationList setOp) {
            setOp.setLimit(null);
            if (setOp.getSelects() != null) {
                for (Select child : setOp.getSelects()) {
                    clearLimit(child);
                }
            }
            return;
        }
        if (select instanceof ParenthesedSelect ps) {
            if (ps.getSelect() != null) {
                clearLimit(ps.getSelect());
            }
            return;
        }
        if (select instanceof PlainSelect plain) {
            plain.setLimit(null);
            return;
        }
        select.setLimit(null);
    }

    /**
     * 去掉文本末尾重复的 LIMIT（门禁/模型叠加时的兜底）。
     */
    static String collapseDuplicateLimits(String sql) {
        if (!StringUtils.hasText(sql)) {
            return sql;
        }
        String s = sql.trim();
        // 反复折叠尾部「LIMIT n LIMIT n」
        Pattern dup = Pattern.compile(
                "(?i)(\\s+LIMIT\\s+\\d+)(\\s+LIMIT\\s+\\d+)+\\s*$");
        Matcher m = dup.matcher(s);
        if (m.find()) {
            s = s.substring(0, m.start(1)) + m.group(1);
        }
        return s;
    }

    static Limit normalizeLimit(Limit existing, long defaultLimit, long maxLimitCap) {
        long def = defaultLimit <= 0 ? 100L : defaultLimit;
        long max = maxLimitCap < def ? def : maxLimitCap;
        long rowCount = def;
        if (existing != null && existing.getRowCount() instanceof LongValue lv) {
            rowCount = lv.getValue();
            if (rowCount <= 0) {
                rowCount = def;
            } else if (rowCount > max) {
                rowCount = max;
            }
        } else if (existing != null && existing.getRowCount() != null) {
            rowCount = def;
        }
        Limit limit = new Limit();
        limit.setRowCount(new LongValue(rowCount));
        if (existing != null && existing.getOffset() != null) {
            limit.setOffset(existing.getOffset());
        }
        return limit;
    }

    /** 任意 SQL 文本的派生表别名兜底 + 重复 LIMIT 折叠。 */
    static String fixSqlText(String sql) {
        return collapseDuplicateLimits(fixMissingDerivedAliases(sql));
    }

    /**
     * 将单条 SQL 中重复的临时表引用自动拆成副本表。
     *
     * @return null 无需处理；否则前面是 CREATE 副本语句，最后一项是改写后的原 SQL
     */
    static List<String> rewriteTempReopen(String sql) {
        if (!StringUtils.hasText(sql)) {
            return null;
        }
        if (TempTableReopenChecker.rejectIfReopen(sql) == null) {
            return null;
        }
        List<String> extras = new ArrayList<>();
        String rewritten = sql;
        for (int guard = 0; guard < 16; guard++) {
            String reject = TempTableReopenChecker.rejectIfReopen(rewritten);
            if (reject == null) {
                break;
            }
            String dup = extractFirstDupTemp(rewritten);
            if (!StringUtils.hasText(dup)) {
                break;
            }
            int n = 2;
            String copy = dup + "_" + n;
            while (containsIdent(rewritten, copy) && n < 30) {
                n++;
                copy = dup + "_" + n;
            }
            String next = replaceOnlyNthOccurrence(rewritten, dup, copy, 2);
            if (next.equals(rewritten)) {
                break;
            }
            rewritten = next;
            extras.add("CREATE TEMPORARY TABLE " + copy + " AS SELECT * FROM " + dup);
        }
        if (extras.isEmpty()) {
            return null;
        }
        List<String> ordered = new ArrayList<>(extras);
        ordered.add(fixMissingDerivedAliases(rewritten));
        return ordered;
    }

    private static String extractFirstDupTemp(String sql) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher tm = Pattern.compile("(?i)\\b((?:tmp_|temp_|_tmp_|_temp_)[A-Za-z0-9_]+)\\b").matcher(sql);
        List<String> order = new ArrayList<>();
        while (tm.find()) {
            String raw = tm.group(1);
            String key = raw.toLowerCase(Locale.ROOT);
            if (!counts.containsKey(key)) {
                order.add(raw);
            }
            counts.merge(key, 1, Integer::sum);
        }
        for (String raw : order) {
            if (counts.getOrDefault(raw.toLowerCase(Locale.ROOT), 0) > 1) {
                return raw;
            }
        }
        return null;
    }

    private static boolean containsIdent(String sql, String ident) {
        return Pattern.compile("(?i)\\b" + Pattern.quote(ident) + "\\b").matcher(sql).find();
    }

    /** 仅替换第 n 次出现（n 从 1 起）。 */
    static String replaceOnlyNthOccurrence(String sql, String target, String replacement, int n) {
        if (!StringUtils.hasText(sql) || !StringUtils.hasText(target) || n < 1) {
            return sql;
        }
        Pattern p = Pattern.compile("(?i)\\b(" + Pattern.quote(target) + ")\\b");
        Matcher m = p.matcher(sql);
        StringBuffer sb = new StringBuffer();
        int hit = 0;
        boolean done = false;
        while (m.find()) {
            hit++;
            if (!done && hit == n) {
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                done = true;
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
