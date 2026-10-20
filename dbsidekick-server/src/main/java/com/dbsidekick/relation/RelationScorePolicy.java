package com.dbsidekick.relation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 关系加减分、强信号与升降级判定。只改内存里的 {@link RelationUsage}，不访问数据库。
 * 阈值和分值全部读 {@link RelationConfigService}。
 */
@Component
public class RelationScorePolicy {

    private final RelationConfigService relationConfigService;

    public RelationScorePolicy(RelationConfigService relationConfigService) {
        this.relationConfigService = relationConfigService;
    }

    /**
     * 带 INFERRED 且没有强信号时，晋升阈值乘 inferred-threshold-multiplier。
     * 强信号：MANUAL / SAVED / LIKED。
     */
    public static int promotionThreshold(String sources, int base, int multiplier) {
        int threshold = base <= 0 ? 6 : base;
        int mul = multiplier <= 0 ? 2 : multiplier;
        if (hasToken(sources, "INFERRED") && !hasStrongSignal(sources)) {
            return threshold * mul;
        }
        return threshold;
    }

    public static boolean hasStrongSignal(String sources) {
        return hasToken(sources, "MANUAL")
                || hasToken(sources, "SAVED")
                || hasToken(sources, "LIKED");
    }

    /** 展示用：CONFIRMED 但无强信号为 AUTO，有强信号为 CONFIRMED。 */
    public static String displayKind(RelationUsage row) {
        if (row == null || !StringUtils.hasText(row.getStatus())) {
            return "PENDING";
        }
        String status = row.getStatus().trim().toUpperCase(Locale.ROOT);
        if (RelationUsage.STATUS_REJECTED.equals(status)) {
            return "REJECTED";
        }
        if (RelationUsage.STATUS_PENDING.equals(status)) {
            return "PENDING";
        }
        if (RelationUsage.STATUS_CONFIRMED.equals(status)) {
            return hasStrongSignal(row.getSources()) ? "CONFIRMED" : "AUTO";
        }
        return status;
    }

    /**
     * 来源明细：每个 source token 与配置加分，供管理页解释为何被确认。
     */
    public List<Map<String, Object>> sourceScoreDetails(String sources) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!StringUtils.hasText(sources)) {
            return out;
        }
        for (String raw : sources.split(",")) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String tag = raw.trim().toUpperCase(Locale.ROOT);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", tag);
            item.put("label", sourceLabel(tag));
            item.put("score", sourceUnitScore(tag));
            item.put("strong", "MANUAL".equals(tag) || "SAVED".equals(tag) || "LIKED".equals(tag));
            out.add(item);
        }
        return out;
    }

    public int scoreFor(UsageContext ctx) {
        int score = 0;
        if (ctx.sqlSuccess()) {
            score += relationConfigService.getInt(RelationConfigService.KEY_SCORE_SQL_SUCCESS);
        }
        if (ctx.rowCount() > 0) {
            score += relationConfigService.getInt(RelationConfigService.KEY_SCORE_ROW_COUNT);
        }
        if (ctx.savedToScript()) {
            score += relationConfigService.getInt(RelationConfigService.KEY_SCORE_SAVED);
        }
        if (ctx.userConfirmed()) {
            score += relationConfigService.getInt(RelationConfigService.KEY_SCORE_CONFIRMED);
        }
        return score;
    }

    public int feedbackDelta(boolean useful) {
        return useful
                ? relationConfigService.getInt(RelationConfigService.KEY_SCORE_LIKED)
                : relationConfigService.getInt(RelationConfigService.KEY_SCORE_DISLIKED);
    }

    public static String feedbackTag(boolean useful) {
        return useful ? "LIKED" : "DISLIKED";
    }

    public int confirmedDelta() {
        return relationConfigService.getInt(RelationConfigService.KEY_SCORE_CONFIRMED);
    }

    public int rejectedScore() {
        return relationConfigService.getInt(RelationConfigService.KEY_SCORE_REJECTED);
    }

    public void applyNewUsage(RelationUsage row, int score, UsageContext ctx, String now) {
        row.setHitCount(1);
        row.setConfidence(score);
        row.setStatus(RelationUsage.STATUS_PENDING);
        row.setSources(ctx.sourceTag());
        row.setFirstSeenAt(now);
        row.setLastUsedAt(now);
        row.setLastUseRows(ctx.rowCount());
    }

    public void applyExistingUsage(RelationUsage row, int score, UsageContext ctx, String now) {
        row.setHitCount(row.getHitCount() + 1);
        row.setConfidence(row.getConfidence() + score);
        row.setSources(appendSource(row.getSources(), ctx.sourceTag()));
        row.setLastUsedAt(now);
        row.setLastUseRows(ctx.rowCount());
    }

    public void applyNewFeedback(RelationUsage row, int delta, String tag, String now) {
        row.setHitCount(0);
        row.setConfidence(delta);
        row.setStatus(RelationUsage.STATUS_PENDING);
        row.setSources(tag);
        row.setFirstSeenAt(now);
        row.setLastUsedAt(now);
    }

    public void applyExistingFeedback(RelationUsage row, int delta, String tag, String now) {
        row.setConfidence(row.getConfidence() + delta);
        row.setSources(appendSource(row.getSources(), tag));
        row.setLastUsedAt(now);
    }

    public void applyManualConfirm(RelationUsage row, String now) {
        row.setStatus(RelationUsage.STATUS_CONFIRMED);
        row.setConfidence(row.getConfidence() + confirmedDelta());
        row.setSources(appendSource(row.getSources(), "MANUAL"));
        row.setLastUsedAt(now);
    }

    public void applyManualReject(RelationUsage row, String now) {
        row.setStatus(RelationUsage.STATUS_REJECTED);
        row.setConfidence(rejectedScore());
        row.setSources(appendSource(row.getSources(), "MANUAL"));
        row.setLastUsedAt(now);
    }

    public void applyReset(RelationUsage row, String now) {
        row.setStatus(RelationUsage.STATUS_PENDING);
        row.setConfidence(0);
        row.setSources(appendSource(row.getSources(), "RESET"));
        row.setLastUsedAt(now);
    }

    public void applyDislikeRejection(RelationUsage row) {
        row.setStatus(RelationUsage.STATUS_REJECTED);
        row.setConfidence(rejectedScore());
    }

    /**
     * 仅 PENDING 且达到阈值（以及强信号要求）时改为 CONFIRMED。
     *
     * @return true 表示本次把状态改成了 CONFIRMED
     */
    public boolean markPromotedIfEligible(RelationUsage row) {
        if (row == null || RelationUsage.STATUS_REJECTED.equals(row.getStatus())) {
            return false;
        }
        if (!RelationUsage.STATUS_PENDING.equals(row.getStatus())) {
            return false;
        }
        if (row.getConfidence() < currentThreshold(row.getSources())) {
            return false;
        }
        if (relationConfigService.requireStrongSignal() && !hasStrongSignal(row.getSources())) {
            return false;
        }
        row.setStatus(RelationUsage.STATUS_CONFIRMED);
        return true;
    }

    /**
     * 已确认关系掉到阈值以下，或丢掉强信号时，改回 PENDING。
     *
     * @return true 表示本次把状态改成了 PENDING
     */
    public boolean markDemotedIfNeeded(RelationUsage row) {
        if (row == null || !RelationUsage.STATUS_CONFIRMED.equals(row.getStatus())) {
            return false;
        }
        boolean below = row.getConfidence() < currentThreshold(row.getSources());
        boolean lostSignal = relationConfigService.requireStrongSignal() && !hasStrongSignal(row.getSources());
        if (below || lostSignal) {
            row.setStatus(RelationUsage.STATUS_PENDING);
            return true;
        }
        return false;
    }

    /** 置信度 v2 迁移：已确认但没有强信号的行要降回 PENDING。 */
    public boolean shouldDemoteConfirmedWithoutStrongSignal(RelationUsage row) {
        return row != null
                && RelationUsage.STATUS_CONFIRMED.equals(row.getStatus())
                && !hasStrongSignal(row.getSources());
    }

    /** 旧默认阈值 3 才抬到 6。 */
    public boolean shouldBumpLegacyThreshold(String value) {
        return "3".equals(value == null ? "" : value.trim());
    }

    public int leadingDislikeStreak(List<String> sourcesNewestFirst) {
        int streak = 0;
        if (sourcesNewestFirst == null) {
            return 0;
        }
        for (String source : sourcesNewestFirst) {
            if ("DISLIKED".equalsIgnoreCase(source == null ? "" : source.trim())) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    public boolean shouldRejectForDislikeStreak(int streak) {
        return streak >= relationConfigService.maxConsecutiveDislikes();
    }

    private int currentThreshold(String sources) {
        return promotionThreshold(
                sources,
                relationConfigService.confidenceThreshold(),
                relationConfigService.getInt(RelationConfigService.KEY_INFERRED_MULTIPLIER, 2));
    }

    private int sourceUnitScore(String tag) {
        return switch (tag) {
            case "USED" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_SQL_SUCCESS)
                    + relationConfigService.getInt(RelationConfigService.KEY_SCORE_ROW_COUNT);
            case "SAVED" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_SAVED);
            case "MANUAL" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_CONFIRMED);
            case "LIKED" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_LIKED);
            case "DISLIKED" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_DISLIKED);
            case "REJECTED" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_REJECTED);
            default -> 0;
        };
    }

    private static String sourceLabel(String tag) {
        return switch (tag) {
            case "USED" -> "执行成功/有行";
            case "SAVED" -> "保存脚本";
            case "MANUAL" -> "手动确认";
            case "LIKED" -> "结果有用";
            case "DISLIKED" -> "结果没用";
            case "INFERRED" -> "推断导入";
            case "RESET" -> "重置";
            default -> tag;
        };
    }

    private static boolean hasToken(String sources, String token) {
        if (!StringUtils.hasText(sources) || !StringUtils.hasText(token)) {
            return false;
        }
        String want = token.trim().toUpperCase(Locale.ROOT);
        for (String part : sources.split(",")) {
            if (want.equals(part.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String appendSource(String sources, String add) {
        Set<String> set = new LinkedHashSet<>();
        if (StringUtils.hasText(sources)) {
            for (String part : sources.split(",")) {
                if (StringUtils.hasText(part)) {
                    set.add(part.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (StringUtils.hasText(add)) {
            set.add(add.trim().toUpperCase(Locale.ROOT));
        }
        return String.join(",", set);
    }
}
