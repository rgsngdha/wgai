package org.jeecg.modules.tab.AIModel.pose;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Point;

/**
 * @author wggg
 * @date 2025/9/26 18:35
 * 改进版：增强跌倒检测准确性，减少误判
 */
@Data
@Slf4j
public class FallDetectionResult {
    private String status; // "站立" 或 "跌倒"
    private float confidence;
    private String reason;
    private boolean isAlert; // 是否需要报警

    public FallDetectionResult(String status, float confidence, String reason, boolean isAlert) {
        this.status = status;
        this.confidence = confidence;
        this.reason = reason;
        this.isAlert = isAlert;
    }

    // 核心跌倒检测算法（改进版）
    public static FallDetectionResult detectFallOrStand(float[] keypoints, double scale, double dx, double dy) {
        // 关键点索引
        final int NOSE = 0, LEFT_EYE = 1, RIGHT_EYE = 2;
        final int LEFT_SHOULDER = 5, RIGHT_SHOULDER = 6;
        final int LEFT_HIP = 11, RIGHT_HIP = 12;
        final int LEFT_KNEE = 13, RIGHT_KNEE = 14;
        final int LEFT_ANKLE = 15, RIGHT_ANKLE = 16;

        // 将关键点坐标还原到原图
        Point[] points = new Point[17];
        boolean[] visible = new boolean[17];

        for (int i = 0; i < 17; i++) {
            float kx = keypoints[i * 3];
            float ky = keypoints[i * 3 + 1];
            float kv = keypoints[i * 3 + 2];

            double px = (kx - dx) / scale;
            double py = (ky - dy) / scale;

            points[i] = new Point(px, py);
            visible[i] = kv > 0.5;
        }

        try {
            // 第一步：检查关键点完整性（防止半身误判）
            KeypointCompleteness completeness = checkKeypointCompleteness(visible,
                    NOSE, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE);

            if (!completeness.isValid) {
                return new FallDetectionResult("无法判断", 0.0f,
                        "关键点不足：" + completeness.reason, false);
            }

            // 第二步：检查身体比例合理性（防止异常姿态）
//            if (!checkBodyProportions(points, visible, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP, LEFT_ANKLE, RIGHT_ANKLE)) {
//                return new FallDetectionResult("无法判断", 0.0f,
//                        "身体比例异常，可能是半身或遮挡", false);
//            }

            // 1. 身体垂直度检测（最重要指标）
            double bodyVerticalScore = calculateBodyVerticalScore(points, visible,
                    NOSE, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP);

            // 2. 身体长宽比检测
            double bodyAspectScore = calculateBodyAspectScore(points, visible,
                    LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP, LEFT_ANKLE, RIGHT_ANKLE);

            // 3. 头部高度检测
            double headHeightScore = calculateHeadHeightScore(points, visible,
                    NOSE, LEFT_ANKLE, RIGHT_ANKLE);

            // 4. 躯干角度检测（新增）
            double torsoAngleScore = calculateTorsoAngleScore(points, visible,
                    LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP);

            // 5. 膝盖位置检测（新增，防止坐姿误判）
            double kneePositionScore = calculateKneePositionScore(points, visible,
                    LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE);

            // 综合评分 (更严格的权重分配)
            double totalScore = bodyVerticalScore * 0.30
                    + bodyAspectScore * 0.25
                    + headHeightScore * 0.20
                    + torsoAngleScore * 0.15
                    + kneePositionScore * 0.10;

            log.info(String.format("跌倒检测详细评分: 垂直度=%.2f, 长宽比=%.2f, 头部高度=%.2f, 躯干角度=%.2f, 膝盖位置=%.2f, 总分=%.2f",
                    bodyVerticalScore, bodyAspectScore, headHeightScore, torsoAngleScore, kneePositionScore, totalScore));

            // 更严格的判断阈值
            if (totalScore < 0.35) {
                return new FallDetectionResult("fall", (float) (1.0 - totalScore),
                        "检测到明显跌倒姿态", true);
            } else if (totalScore > 0.75) {
                return new FallDetectionResult("stand", (float) totalScore,
                        "正常站立姿态", true);
            } else {
                return new FallDetectionResult("不确定", (float) totalScore,
                        "姿态不明确（可能是坐/蹲/弯腰）", false);
            }

        } catch (Exception e) {
            log.error("跌倒检测异常", e);
            return new FallDetectionResult("检测错误", 0.0f, "无法分析姿态: " + e.getMessage(), false);
        }
    }

    // 关键点完整性检查
    private static class KeypointCompleteness {
        boolean isValid;
        String reason;

        KeypointCompleteness(boolean isValid, String reason) {
            this.isValid = isValid;
            this.reason = reason;
        }
    }

    private static KeypointCompleteness checkKeypointCompleteness(boolean[] visible,
                                                                  int nose, int leftShoulder, int rightShoulder, int leftHip, int rightHip,
                                                                  int leftKnee, int rightKnee, int leftAnkle, int rightAnkle) {

        // 必须有头部
//        if (!visible[nose]) {
//            return new KeypointCompleteness(false, "头部不可见");
//        }

        // 必须有双肩或至少一侧完整
        boolean hasShoulders = visible[leftShoulder] && visible[rightShoulder];
        if (!hasShoulders) {
            return new KeypointCompleteness(false, "肩部关键点不足");
        }

        // 必须有臀部
//        boolean hasHips = visible[leftHip] || visible[rightHip];
//        if (!hasHips) {
//            return new KeypointCompleteness(false, "臀部不可见");
//        }

        // 至少要有一条腿的关键点（髋-膝-踝）
        boolean hasLeftLeg = visible[leftHip] && visible[leftKnee] && visible[leftAnkle];
        boolean hasRightLeg = visible[rightHip] && visible[rightKnee] && visible[rightAnkle];

        if (!hasLeftLeg && !hasRightLeg) {
            return new KeypointCompleteness(false, "腿部关键点不足");
        }

        return new KeypointCompleteness(true, "关键点完整");
    }

    // 检查身体比例合理性（防止半身图像）
    private static boolean checkBodyProportions(Point[] points, boolean[] visible,
                                                int leftShoulder, int rightShoulder, int leftHip, int rightHip, int leftAnkle, int rightAnkle) {

        if (!visible[leftShoulder] || !visible[rightShoulder]) {
            return false;
        }

        // 计算肩宽
        double shoulderWidth = Math.abs(points[leftShoulder].x - points[rightShoulder].x);

        // 计算身体高度
        double bodyHeight = 0;
        int heightCount = 0;

        if (visible[leftHip] && visible[leftAnkle]) {
            bodyHeight += Math.abs(points[leftShoulder].y - points[leftAnkle].y);
            heightCount++;
        }
        if (visible[rightHip] && visible[rightAnkle]) {
            bodyHeight += Math.abs(points[rightShoulder].y - points[rightAnkle].y);
            heightCount++;
        }

        if (heightCount == 0) return false;
        bodyHeight /= heightCount;

        // 正常人体比例：身高约为肩宽的3-5倍
        double heightToWidthRatio = bodyHeight / shoulderWidth;

        // 如果比例异常（太扁或太窄），可能是半身或遮挡
        if (heightToWidthRatio < 2.0 || heightToWidthRatio > 8.0) {
            log.warn("身体比例异常: 高宽比=" + heightToWidthRatio);
            return false;
        }

        return true;
    }

    // 计算身体垂直度评分（改进版）
    private static double calculateBodyVerticalScore(Point[] points, boolean[] visible,
                                                     int nose, int leftShoulder, int rightShoulder, int leftHip, int rightHip) {

        if (!visible[leftShoulder] || !visible[rightShoulder] || !visible[leftHip] || !visible[rightHip]) {
            return 0.5;
        }

        Point shoulderCenter = new Point(
                (points[leftShoulder].x + points[rightShoulder].x) / 2,
                (points[leftShoulder].y + points[rightShoulder].y) / 2
        );

        Point hipCenter = new Point(
                (points[leftHip].x + points[rightHip].x) / 2,
                (points[leftHip].y + points[rightHip].y) / 2
        );

        double torsoLength = Math.abs(shoulderCenter.y - hipCenter.y);
        double torsoWidth = Math.abs(shoulderCenter.x - hipCenter.x);

        if (torsoLength < 10) return 0.0;

        // 改进：更严格的垂直度计算
        double verticalRatio = torsoLength / (torsoWidth + 1);

        // 站立时比值通常 > 4，跌倒时 < 1
        if (verticalRatio > 4.0) {
            return 1.0;
        } else if (verticalRatio < 1.0) {
            return 0.0;
        } else {
            return (verticalRatio - 1.0) / 3.0;
        }
    }

    // 计算身体长宽比评分（改进版）
    private static double calculateBodyAspectScore(Point[] points, boolean[] visible,
                                                   int leftShoulder, int rightShoulder, int leftHip, int rightHip, int leftAnkle, int rightAnkle) {

        if (!visible[leftShoulder] || !visible[rightShoulder]) {
            return 0.5;
        }

        double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

        int[] keyPointsToCheck = {leftShoulder, rightShoulder, leftHip, rightHip, leftAnkle, rightAnkle};

        for (int idx : keyPointsToCheck) {
            if (visible[idx]) {
                minX = Math.min(minX, points[idx].x);
                maxX = Math.max(maxX, points[idx].x);
                minY = Math.min(minY, points[idx].y);
                maxY = Math.max(maxY, points[idx].y);
            }
        }

        double bodyWidth = maxX - minX;
        double bodyHeight = maxY - minY;

        if (bodyWidth < 10 || bodyHeight < 10) return 0.5;

        double aspectRatio = bodyHeight / bodyWidth;

        // 更严格的阈值：站立 > 2.0，跌倒 < 1.0
        if (aspectRatio > 2.0) {
            return 1.0;
        } else if (aspectRatio < 1.0) {
            return 0.0;
        } else {
            return (aspectRatio - 1.0) / 1.0;
        }
    }

    // 计算头部高度评分（改进版）
    private static double calculateHeadHeightScore(Point[] points, boolean[] visible,
                                                   int nose, int leftAnkle, int rightAnkle) {

        if (!visible[nose]) {
            return 0.5;
        }

        double groundLevel = 0;
        int validAnkles = 0;

        if (visible[leftAnkle]) {
            groundLevel += points[leftAnkle].y;
            validAnkles++;
        }
        if (visible[rightAnkle]) {
            groundLevel += points[rightAnkle].y;
            validAnkles++;
        }

        if (validAnkles == 0) return 0.5;
        groundLevel /= validAnkles;

        double headHeight = groundLevel - points[nose].y;

        // 更严格的阈值
        if (headHeight < 80) {
            return 0.0; // 头部很低
        } else if (headHeight > 250) {
            return 1.0; // 头部很高
        } else {
            return (headHeight - 80) / 170.0;
        }
    }

    // 新增：计算躯干角度评分
    private static double calculateTorsoAngleScore(Point[] points, boolean[] visible,
                                                   int leftShoulder, int rightShoulder, int leftHip, int rightHip) {

        if (!visible[leftShoulder] || !visible[rightShoulder] || !visible[leftHip] || !visible[rightHip]) {
            return 0.5;
        }

        Point shoulderCenter = new Point(
                (points[leftShoulder].x + points[rightShoulder].x) / 2,
                (points[leftShoulder].y + points[rightShoulder].y) / 2
        );

        Point hipCenter = new Point(
                (points[leftHip].x + points[rightHip].x) / 2,
                (points[leftHip].y + points[rightHip].y) / 2
        );

        // 计算躯干与垂直线的角度
        double dx = hipCenter.x - shoulderCenter.x;
        double dy = hipCenter.y - shoulderCenter.y;

        double angle = Math.abs(Math.toDegrees(Math.atan2(dx, dy)));

        // 站立时角度接近0度，跌倒时接近90度
        if (angle < 15) {
            return 1.0; // 非常垂直
        } else if (angle > 70) {
            return 0.0; // 非常水平
        } else {
            return 1.0 - (angle - 15) / 55.0;
        }
    }

    // 新增：计算膝盖位置评分（区分站立、坐姿、跌倒）
    private static double calculateKneePositionScore(Point[] points, boolean[] visible,
                                                     int leftHip, int rightHip, int leftKnee, int rightKnee, int leftAnkle, int rightAnkle) {

        // 至少需要一条腿的完整信息
        boolean hasLeftLeg = visible[leftHip] && visible[leftKnee] && visible[leftAnkle];
        boolean hasRightLeg = visible[rightHip] && visible[rightKnee] && visible[rightAnkle];

        if (!hasLeftLeg && !hasRightLeg) {
            return 0.5;
        }

        double score = 0.0;
        int legCount = 0;

        // 检查左腿
        if (hasLeftLeg) {
            double hipToKnee = Math.abs(points[leftKnee].y - points[leftHip].y);
            double kneeToAnkle = Math.abs(points[leftAnkle].y - points[leftKnee].y);

            // 站立时，大腿和小腿应该接近伸直（y坐标差值较大）
            double legExtension = (hipToKnee + kneeToAnkle) / 2;

            if (legExtension > 100) {
                score += 1.0; // 腿部伸展，可能站立
            } else if (legExtension < 50) {
                score += 0.0; // 腿部蜷缩，可能跌倒或坐
            } else {
                score += (legExtension - 50) / 50.0;
            }
            legCount++;
        }

        // 检查右腿
        if (hasRightLeg) {
            double hipToKnee = Math.abs(points[rightKnee].y - points[rightHip].y);
            double kneeToAnkle = Math.abs(points[rightAnkle].y - points[rightKnee].y);

            double legExtension = (hipToKnee + kneeToAnkle) / 2;

            if (legExtension > 100) {
                score += 1.0;
            } else if (legExtension < 50) {
                score += 0.0;
            } else {
                score += (legExtension - 50) / 50.0;
            }
            legCount++;
        }

        return score / legCount;
    }
}