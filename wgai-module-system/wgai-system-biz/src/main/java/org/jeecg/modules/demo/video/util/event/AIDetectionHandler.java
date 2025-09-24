package org.jeecg.modules.demo.video.util.event;

import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.util.identifyTypeNew;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.opencv.core.Mat;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;



/**
 * 阶段3：AI检测处理器组 - 多个实例并行处理
 * 目标：最大化CPU利用率
 * @author wggg
 * @date 2025/8/1 9:33
 */
@Slf4j
public  class AIDetectionHandler implements EventHandler<FrameProcessEvent> {
    private final String handlerName;
    private final AtomicLong processedCount = new AtomicLong(0);
    private final RedisTemplate redisTemplate;

    public AIDetectionHandler(String handlerName, RedisTemplate redisTemplate) {
        this.handlerName = handlerName;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void onEvent(FrameProcessEvent event, long sequence, boolean endOfBatch) throws Exception {
        if (event.getError() != null) {
            // 有错误时也要清理资源
            cleanupEvent(event);
            return; // 跳过有错误的事件
        }

        long startTime = System.nanoTime();

        try {
            Mat mat = event.getMat();
            List<NetPush> netPushList = event.getNetPushList();
            TabAiSubscriptionNew subscription = event.getSubscription();
            identifyTypeNew identifyType = event.getIdentifyType();

            if (mat == null || netPushList == null || subscription == null) {
                event.setError(new IllegalArgumentException("处理数据不完整"));
                return;
            }

            log.debug("[{}] 开始AI检测 流: {}, 推送数量: {}",
                    handlerName, event.getStreamName(), netPushList.size());

            // 并行处理所有NetPush - 充分利用CPU
            netPushList.parallelStream().forEach(netPush -> {
                try {
                    // 创建Mat副本避免并发问题
                    Mat matCopy = new Mat();
                    mat.copyTo(matCopy);

                    processNetPush(matCopy, netPush, identifyType, subscription, redisTemplate);

                    // 立即释放副本
                    matCopy.release();

                } catch (Exception e) {
                    log.error("[{}] NetPush处理异常 模型: {}",
                            handlerName,
                            netPush.getTabAiModel() != null ? netPush.getTabAiModel().getAiName() : "unknown",
                            e);
                }
            });

            event.setProcessed(true);

            // 性能统计
            long count = processedCount.incrementAndGet();
            long processTime = System.nanoTime() - startTime;

            if (count % 100 == 0) {
                log.info("[{}] AI检测完成第{}次，耗时: {}ms，流: {}",
                        handlerName, count, processTime / 1_000_000, event.getStreamName());
            }

        } catch (Exception e) {
            log.error("[{}] AI检测处理异常 流: {}", handlerName, event.getStreamName(), e);
            event.setError(e);
        } finally {
            // 清理资源
            cleanupEvent(event);
        }
    }

    /**
     * 使用你原有的NetPush处理逻辑
     */
    private static void processNetPush(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll,
                                       TabAiSubscriptionNew subscription, RedisTemplate redisTemplate) {
        try {
            if (netPush.getIsBefor() == 1) { // 有前置
                processWithPredecessors(mat, netPush, identifyTypeAll, subscription, redisTemplate);
            } else { // 无前置
                processWithoutPredecessors(mat, netPush, identifyTypeAll, subscription, redisTemplate);
            }
        } catch (Exception e) {
            log.error("[处理NetPush异常] 模型: {}",
                    netPush.getTabAiModel() != null ? netPush.getTabAiModel().getAiName() : "unknown", e);
        }
    }

    private static void processWithoutPredecessors(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll,
                                                   TabAiSubscriptionNew subscription, RedisTemplate redisTemplate) {
        executeDetection(mat, netPush, identifyTypeAll, subscription, redisTemplate);
    }

    private static void processWithPredecessors(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll,
                                                TabAiSubscriptionNew subscription, RedisTemplate redisTemplate) {
        List<NetPush> before = netPush.getListNetPush();
        if (before == null || before.isEmpty()) {
            return;
        }

        boolean validationPassed = true;

        for (int i = 0; i < before.size() && validationPassed; i++) {
            NetPush beforePush = before.get(i);

            if (i == 0) {
                validationPassed = validateFirstModel(mat, beforePush, identifyTypeAll, subscription);
                if (!validationPassed) {
                    break;
                }
            }

            executeDetection(mat, beforePush, identifyTypeAll, subscription, redisTemplate);
        }
    }

    private static void executeDetection(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll,
                                         TabAiSubscriptionNew subscription, RedisTemplate redisTemplate) {
        try {
            if ("1".equals(netPush.getModelType())) {
                identifyTypeAll.detectObjectsDify(subscription, mat, netPush, redisTemplate,null);
            } else {
                identifyTypeAll.detectObjectsDifyV5(subscription, mat, netPush, redisTemplate,null);
            }
        } catch (Exception e) {
            log.error("[执行检测异常] 模型类型: {}", netPush.getModelType(), e);
        }
    }



    private static boolean validateFirstModel(Mat mat, NetPush beforePush, identifyTypeNew identifyTypeAll,
                                              TabAiSubscriptionNew subscription) {
        try {
//            if ("1".equals(beforePush.getModelType())) {
//                return identifyTypeAll.detectObjects(subscription, mat, beforePush.getNet(),
//                        beforePush.getClaseeNames(), beforePush);
//            } else {
//                return identifyTypeAll.detectObjectsV5(subscription, mat, beforePush.getNet(),
//                        beforePush.getClaseeNames(), beforePush);
//            }
            return  true;
        } catch (Exception e) {
            log.error("[验证模型异常]", e);
            return false;
        }
    }
    private void cleanupEvent(FrameProcessEvent event) {
        // 释放所有资源
        try {
            // 清理资源但保留引用，避免并发问题
            event.cleanupResources();
        } catch (Exception e) {
            log.debug("[{}] 清理事件资源异常，忽略: {}", handlerName, e.getMessage());
        }
        // Frame已经在第一阶段释放了
    }
}

