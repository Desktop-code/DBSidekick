package com.dbsidekick.relation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 从 SQL 顶层 JOIN ON / WHERE 等值条件提取表关系（JSqlParser，无 LLM）。
 * 多语句脚本只取最后一条 SELECT。排除列与临时表前缀来自 {@link RelationConfigService}。
 */
@Component
public class JoinExtractor {

    private static final Logger log = LoggerFactory.getLogger(JoinExtractor.class);

    private final RelationConfigService relationConfigService;

    public JoinExtractor(RelationConfigService relationConfigService) {
        this.relationConfigService = relationConfigService;
    }

    public List<RelationPair> extract(String sql) {
        Set<String> excluded = relationConfigService == null
                ? Set.of()
                : relationConfigService.excludedColumnNames();
        List<String> prefixes = relationConfigService == null
                ? List.of()
                : relationConfigService.tempTablePrefixes();
        return extract(sql, excluded, prefixes);
    }

    public List<RelationPair> extract(String sql, Set<String> excludedColumns, List<String> tempPrefixes) {
        if (!StringUtils.hasText(sql)) {
            return List.of();
        }
        PlainSelect plain = lastPlainSelect(sql);
        if (plain == null) {
            return List.of();
        }
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        collectFromItem(plain.getFromItem(), aliasToTable);

        LinkedHashSet<RelationPair> pairs = new LinkedHashSet<>();
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                collectFromItem(join.getRightItem(), aliasToTable);
                Expression on = join.getOnExpression();
                if (on != null) {
                    collectEquals(on, aliasToTable, pairs, excludedColumns, tempPrefixes);
                }
            }
        }
        if (plain.getWhere() != null) {
            collectEquals(plain.getWhere(), aliasToTable, pairs, excludedColumns, tempPrefixes);
        }
        return new ArrayList<>(pairs);
    }

    private PlainSelect lastPlainSelect(String sql) {
        try {
            Statements stmts = CCJSqlParserUtil.parseStatements(sql);
            PlainSelect last = null;
            if (stmts != null && stmts.getStatements() != null) {
                for (Statement s : stmts.getStatements()) {
                    PlainSelect p = asPlain(s);
                    if (p != null) {
                        last = p;
                    }
                }
            }
            if (last != null) {
                return last;
            }
        } catch (Exception ex) {
            log.debug("[Sidekick][join-extract] statements parse skip: {}", ex.getMessage());
        }
        PlainSelect last = null;
        for (String part : splitLoose(sql)) {
            try {
                PlainSelect p = asPlain(CCJSqlParserUtil.parse(part));
                if (p != null) {
                    last = p;
                }
            } catch (Exception ignored) {
                // 单段解析失败则跳过（例如临时表 DDL）
            }
        }
        return last;
    }

    private static PlainSelect asPlain(Statement stmt) {
        if (stmt instanceof PlainSelect plain) {
            return plain;
        }
        return null;
    }

    private static List<String> splitLoose(String sql) {
        List<String> parts = new ArrayList<>();
        for (String part : sql.split(";")) {
            if (StringUtils.hasText(part)) {
                parts.add(part.trim());
            }
        }
        return parts;
    }

    private void collectFromItem(FromItem item, Map<String, String> aliasToTable) {
        if (item == null) {
            return;
        }
        if (item instanceof ParenthesedSelect) {
            return;
        }
        if (item instanceof Table table) {
            String name = cleanName(table.getName());
            if (!StringUtils.hasText(name)) {
                return;
            }
            aliasToTable.put(name.toLowerCase(Locale.ROOT), name);
            if (table.getAlias() != null && StringUtils.hasText(table.getAlias().getName())) {
                aliasToTable.put(table.getAlias().getName().toLowerCase(Locale.ROOT), name);
            }
        }
    }

    private void collectEquals(Expression expr, Map<String, String> aliasToTable, Set<RelationPair> out,
                               Set<String> excludedColumns, List<String> tempPrefixes) {
        if (expr == null) {
            return;
        }
        if (expr instanceof EqualsTo eq) {
            maybeAdd(eq.getLeftExpression(), eq.getRightExpression(), aliasToTable, out, excludedColumns, tempPrefixes);
            return;
        }
        if (expr instanceof AndExpression and) {
            collectEquals(and.getLeftExpression(), aliasToTable, out, excludedColumns, tempPrefixes);
            collectEquals(and.getRightExpression(), aliasToTable, out, excludedColumns, tempPrefixes);
            return;
        }
        if (expr instanceof OrExpression or) {
            collectEquals(or.getLeftExpression(), aliasToTable, out, excludedColumns, tempPrefixes);
            collectEquals(or.getRightExpression(), aliasToTable, out, excludedColumns, tempPrefixes);
        }
    }

    private void maybeAdd(Expression left, Expression right,
                          Map<String, String> aliasToTable, Set<RelationPair> out,
                          Set<String> excludedColumns, List<String> tempPrefixes) {
        if (!(left instanceof Column lc) || !(right instanceof Column rc)) {
            return;
        }
        ColRef a = resolveColumn(lc, aliasToTable);
        ColRef b = resolveColumn(rc, aliasToTable);
        if (a == null || b == null) {
            return;
        }
        if (a.table.equalsIgnoreCase(b.table)) {
            return;
        }
        if (isExcluded(a.column, excludedColumns) || isExcluded(b.column, excludedColumns)) {
            return;
        }
        if (isTemp(a.table, tempPrefixes) || isTemp(b.table, tempPrefixes)) {
            return;
        }
        out.add(RelationPair.normalized(a.table, a.column, b.table, b.column));
    }

    private ColRef resolveColumn(Column col, Map<String, String> aliasToTable) {
        String column = cleanName(col.getColumnName());
        if (!StringUtils.hasText(column)) {
            return null;
        }
        String tableRef = null;
        if (col.getTable() != null) {
            tableRef = cleanName(col.getTable().getName());
        }
        if (!StringUtils.hasText(tableRef)) {
            return null;
        }
        String real = aliasToTable.get(tableRef.toLowerCase(Locale.ROOT));
        if (!StringUtils.hasText(real)) {
            real = tableRef;
        }
        return new ColRef(real, column);
    }

    private static boolean isExcluded(String column, Set<String> excludedColumns) {
        if (excludedColumns == null || excludedColumns.isEmpty() || column == null) {
            return false;
        }
        return excludedColumns.contains(column.toLowerCase(Locale.ROOT));
    }

    private static boolean isTemp(String table, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty() || table == null) {
            return false;
        }
        String name = table.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (StringUtils.hasText(prefix) && name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").trim();
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) {
            n = n.substring(dot + 1);
        }
        return n;
    }

    private record ColRef(String table, String column) {
    }
}
