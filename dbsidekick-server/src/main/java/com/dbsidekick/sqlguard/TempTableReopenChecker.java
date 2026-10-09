package com.dbsidekick.sqlguard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.util.StringUtils;

/**
 * 检测单条 SQL 内同一临时表被引用多次（MySQL Can't reopen table）。
 */
final class TempTableReopenChecker {

    private static final Pattern TEMP_NAME =
            Pattern.compile("^(tmp_|temp_|_tmp_|_temp_).+", Pattern.CASE_INSENSITIVE);

    private TempTableReopenChecker() {
    }

    /**
     * @return null 通过；否则拒绝原因（与 UserFacingErrors / 重试提示对齐）
     */
    static String rejectIfReopen(String sql) {
        if (!StringUtils.hasText(sql)) {
            return null;
        }
        try {
            String bare = SqlCommentAnnotator.stripLeadingComments(sql).trim();
            Statement st = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(bare);
            Map<String, Integer> counts = new LinkedHashMap<>();
            collectStatement(st, counts);
            List<String> bad = new ArrayList<>();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() != null && e.getValue() > 1) {
                    bad.add(e.getKey() + "×" + e.getValue());
                }
            }
            if (bad.isEmpty()) {
                return null;
            }
            return "MySQL 限制：同一条 SQL 不能两次打开同一张临时表（"
                    + String.join(", ", bad)
                    + "）。请先 CREATE TEMPORARY TABLE tmp_xxx_2 AS SELECT * FROM tmp_xxx，再分别关联。";
        } catch (Exception ex) {
            // 解析失败交给既有门禁
            return null;
        }
    }

    private static void collectStatement(Statement st, Map<String, Integer> counts) {
        if (st instanceof Select select) {
            collectSelect(select, counts);
        } else if (st instanceof CreateTable create && create.getSelect() != null) {
            collectSelect(create.getSelect(), counts);
        }
    }

    private static void collectSelect(Select select, Map<String, Integer> counts) {
        if (select == null) {
            return;
        }
        if (select.getWithItemsList() != null) {
            for (WithItem w : select.getWithItemsList()) {
                if (w != null && w.getSelect() != null) {
                    collectSelect(w.getSelect(), counts);
                }
            }
        }
        if (select instanceof PlainSelect plain) {
            collectPlain(plain, counts);
            return;
        }
        if (select instanceof ParenthesedSelect ps) {
            if (ps.getSelect() != null) {
                collectSelect(ps.getSelect(), counts);
            }
            return;
        }
        if (select instanceof SetOperationList setOp && setOp.getSelects() != null) {
            for (Select s : setOp.getSelects()) {
                collectSelect(s, counts);
            }
        }
    }

    private static void collectPlain(PlainSelect plain, Map<String, Integer> counts) {
        collectFromItem(plain.getFromItem(), counts);
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                if (join != null) {
                    collectFromItem(join.getRightItem(), counts);
                    if (join.getOnExpression() != null) {
                        collectExpr(join.getOnExpression(), counts);
                    }
                }
            }
        }
        if (plain.getWhere() != null) {
            collectExpr(plain.getWhere(), counts);
        }
        if (plain.getHaving() != null) {
            collectExpr(plain.getHaving(), counts);
        }
        if (plain.getSelectItems() != null) {
            for (SelectItem<?> item : plain.getSelectItems()) {
                if (item != null && item.getExpression() != null) {
                    collectExpr(item.getExpression(), counts);
                }
            }
        }
    }

    private static void collectFromItem(FromItem item, Map<String, Integer> counts) {
        if (item == null) {
            return;
        }
        if (item instanceof Table table) {
            bumpTemp(table.getName(), counts);
            return;
        }
        if (item instanceof ParenthesedSelect ps) {
            if (ps.getSelect() != null) {
                collectSelect(ps.getSelect(), counts);
            }
            return;
        }
        if (item instanceof Select nested) {
            collectSelect(nested, counts);
        }
    }

    private static void collectExpr(Expression expr, Map<String, Integer> counts) {
        if (expr == null) {
            return;
        }
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(ParenthesedSelect parenthesedSelect) {
                if (parenthesedSelect.getSelect() != null) {
                    collectSelect(parenthesedSelect.getSelect(), counts);
                }
            }

            @Override
            public void visit(InExpression inExpression) {
                super.visit(inExpression);
                if (inExpression.getRightExpression() instanceof ParenthesedSelect ps) {
                    collectSelect(ps.getSelect(), counts);
                } else if (inExpression.getRightExpression() instanceof Select s) {
                    collectSelect(s, counts);
                }
            }

            @Override
            public void visit(ExistsExpression existsExpression) {
                super.visit(existsExpression);
                if (existsExpression.getRightExpression() instanceof ParenthesedSelect ps) {
                    collectSelect(ps.getSelect(), counts);
                } else if (existsExpression.getRightExpression() instanceof Select s) {
                    collectSelect(s, counts);
                }
            }
        });
    }

    private static void bumpTemp(String name, Map<String, Integer> counts) {
        String n = clean(name);
        if (!StringUtils.hasText(n)) {
            return;
        }
        if (!TEMP_NAME.matcher(n).matches()) {
            return;
        }
        String key = n.toLowerCase(Locale.ROOT);
        counts.merge(key, 1, Integer::sum);
    }

    private static String clean(String name) {
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
}
