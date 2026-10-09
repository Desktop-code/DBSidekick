package com.dbsidekick.text2sql;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启发式判断是否需要脚本模式（多步 SQL），以及是否追问。
 */
@Component
public class QueryComplexityAnalyzer {

    private static final Pattern COMPLEX_KW = Pattern.compile(
            "复购率|留存|漏斗|同比|环比|分步骤|临时表|先.+再|然后再|并且|然后");

    private static final Pattern REF_WORDS = Pattern.compile(
            "重新|再|这个|那个|它|刚才|上面|前一个|改成|带上|加上|同上|继续|还是");

    private static final Pattern ACTION_VERBS = Pattern.compile(
            "查|统计|列出|显示|查询|分析|汇总|计算|找出|获取|导出|对比");

    private static final Pattern NOUNISH = Pattern.compile(
            "订单|商品|店铺|门店|客户|用户|销量|销售|金额|库存|退货|会员|支付|表|字段|"
                    + "order|product|shop|store|user|customer|sales|amount|stock",
            Pattern.CASE_INSENSITIVE);

    public boolean needsScriptMode(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String q = question.trim();
        if (q.length() > 50) {
            return true;
        }
        long qmarks = q.chars().filter(c -> c == '?' || c == '？').count();
        if (qmarks >= 2) {
            return true;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        if (COMPLEX_KW.matcher(q).find() || COMPLEX_KW.matcher(lower).find()) {
            return true;
        }
        return q.contains("并且") || q.contains("然后") || q.contains("再统计") || q.contains("再计算");
    }

    /**
     * 追问检测：满足任意 2 条启发式即视为追问。
     * <ul>
     *   <li>长度 &lt; 15</li>
     *   <li>含指代/修正词</li>
     *   <li>无明显查询动词</li>
     *   <li>不含业务名词关键词</li>
     * </ul>
     */
    public boolean isFollowUp(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String q = question.trim();
        int score = 0;
        if (q.length() < 15) {
            score++;
        }
        if (REF_WORDS.matcher(q).find()) {
            score++;
        }
        if (!ACTION_VERBS.matcher(q).find()) {
            score++;
        }
        if (!NOUNISH.matcher(q).find()) {
            score++;
        }
        return score >= 2;
    }
}
