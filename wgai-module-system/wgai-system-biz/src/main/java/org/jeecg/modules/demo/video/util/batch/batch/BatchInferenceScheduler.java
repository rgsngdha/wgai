package org.jeecg.modules.demo.video.util.batch.batch;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.opencv.core.Mat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量推理调度器 - 修复版
 * 核心改进:
 * 1. 修复内存泄漏问题
 * 2. 优化批量收集策略
 * 3. 支持真正的批量推理
 * 4. 添加姿态识别批量处理
 */
@Slf4j
@Component
public class BatchInferenceScheduler {

    @Autowired
    private RedisTemplate redisTemplate;

    // ============ 配置参数 ============
    private static final int MAX_BATCH_SIZE = 6;
    private static final long BATCH_WAIT_MS = 30;
    private static final int WORKER_THREADS = 4;
    private static final int QUEUE_CAPACITY = 100;

    // ============ 数据结构 ============

    /**
     * 完整推理任务
     */
    public static class FullInferenceTask {
        String streamId;
        Mat frame;
        List<NetPush> netPushList;
        TabAiSubscriptionNew pushInfo;
        CompletableFuture<Boolean> future;
        long submitTime;
        volatile boolean frameClaimed = false; // 标记Mat是否已被接管

        public FullInferenceTask(String streamId, Mat frame,
                                 List<NetPush> netPushList,
                                 TabAiSubscriptionNew pushInfo) {
            this.streamId = streamId;
            this.frame = frame;
            this.netPushList = netPushList;
            this.pushInfo = pushInfo;
            this.future = new CompletableFuture<>();
            this.submitTime = System.currentTimeMillis();
        }

        public void claimFrame() {
            frameClaimed = true;
        }

        public void releaseFrame() {
            if (frame != null && !frameClaimed) {
                frame.release();
                frame = null;
            }
        }
    }

    /**
     * 批量任务基类
     */
    private static abstract class BatchTask<T> {
        String streamId;
        Mat frame;
        NetPush netPush;
        TabAiSubscriptionNew pushInfo;
        CompletableFuture<T> future;
        long createTime;

        BatchTask(String streamId, Mat frame, NetPush netPush,
                  TabAiSubscriptionNew pushInfo) {
            this.streamId = streamId;
            this.frame = frame;
            this.netPush = netPush;
            this.pushInfo = pushInfo;
            this.future = new CompletableFuture<>();
            this.createTime = System.currentTimeMillis();
        }

        void cleanup() {
            if (frame != null) {
                frame.release();
                frame = null;
            }
        }
    }

    /**
     * 前置模型任务
     */
    private static class PreModelTask extends BatchTask<retureBoxInfo> {
        PreModelTask(String streamId, Mat frame, NetPush netPush,
                     TabAiSubscriptionNew pushInfo) {
            super(streamId, frame, netPush, pushInfo);
        }
    }

    /**
     * 后置模型任务
     */
    private static class PostModelTask extends BatchTask<Boolean> {
        List<retureBoxInfo> preResults;

        PostModelTask(String streamId, Mat frame, NetPush netPush,
                      TabAiSubscriptionNew pushInfo, List<retureBoxInfo> preResults) {
            super(streamId, frame, netPush, pushInfo);
            this.preResults = preResults;
        }
    }

    /**
     * 姿态识别任务
     */
    private static class PoseModelTask extends BatchTask<Boolean> {
        List<retureBoxInfo> preResults;

        PoseModelTask(String streamId, Mat frame, NetPush netPush,
                      TabAiSubscriptionNew pushInfo, List<retureBoxInfo> preResults) {
            super(streamId, frame, netPush, pushInfo);
            this.preResults = preResults;
        }
    }

    // ============ 队列 ============
    private final BlockingQueue<FullInferenceTask> mainQueue =
            new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final ConcurrentHashMap<String, BlockingQueue<PreModelTask>> preModelQueues =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, BlockingQueue<PostModelTask>> postModelQueues =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, BlockingQueue<PoseModelTask>> poseModelQueues =
            new ConcurrentHashMap<>();

    // ============ 线程池 ============
    private final ExecutorService mainExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "MainScheduler")
    );

    private final ExecutorService preExecutor = Executors.newFixedThreadPool(
            WORKER_THREADS / 2,
            r -> new Thread(r, "PreModel-Worker")
    );

    private final ExecutorService postExecutor = Executors.newFixedThreadPool(
            WORKER_THREADS / 2,
            r -> new Thread(r, "PostModel-Worker")
    );

    private final ExecutorService poseExecutor = Executors.newFixedThreadPool(
            2,
            r -> new Thread(r, "PoseModel-Worker")
    );

    private volatile boolean running = true;

    // ============ 统计 ============
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalBatches = new AtomicLong(0);

    // ============ 初始化 ============
    @PostConstruct
    public void init() {
        log.info("[批量推理调度器启动] 批次大小:{}, 工作线程:{}",
                MAX_BATCH_SIZE, WORKER_THREADS);

        mainExecutor.submit(new MainScheduler());

        for (int i = 0; i < WORKER_THREADS / 2; i++) {
            preExecutor.submit(new PreModelWorker());
            postExecutor.submit(new PostModelWorker());
        }

        for (int i = 0; i < 2; i++) {
            poseExecutor.submit(new PoseModelWorker());
        }

        // 启动监控线程
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                this::logStatistics, 30, 30, TimeUnit.SECONDS
        );
    }

    @PreDestroy
    public void shutdown() {
        log.info("[开始关闭调度器]");
        running = false;

        shutdownExecutor(mainExecutor, "MainScheduler");
        shutdownExecutor(preExecutor, "PreModel");
        shutdownExecutor(postExecutor, "PostModel");
        shutdownExecutor(poseExecutor, "PoseModel");

        log.info("[调度器关闭完成]");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("[{}强制关闭]", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    // ============ 公共接口 ============
    public CompletableFuture<Boolean> submitFullInference(
            String streamId,
            Mat frame,
            List<NetPush> netPushList,
            TabAiSubscriptionNew pushInfo) {

        FullInferenceTask task = new FullInferenceTask(
                streamId, frame, netPushList, pushInfo
        );

        if (!mainQueue.offer(task)) {
            log.warn("[主队列已满,丢弃任务] 摄像头:{}", streamId);
            task.future.complete(false);
            task.releaseFrame();
        }

        return task.future;
    }


// ============ 主调度器 - 修复版 ============

    private class MainScheduler implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    FullInferenceTask task = mainQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task == null) continue;

                    processFullTask(task);

                } catch (Exception e) {
                    log.error("[主调度器异常]", e);
                }
            }
        }

        private void processFullTask(FullInferenceTask task) {
            try {
                if (task.netPushList == null || task.netPushList.isEmpty()) {
                    log.warn("[模型列表为空] streamId:{}", task.streamId);
                    task.future.complete(false);
                    task.releaseFrame();
                    return;
                }

                NetPush firstPush = task.netPushList.get(0);

                // ========== 核心分类逻辑 ==========

                // 情况1: 有前置模型 (IsBefor=0 且 ListNetPush不为空)
                if (firstPush.getIsBefor() == 0 &&
                        firstPush.getListNetPush() != null &&
                        !firstPush.getListNetPush().isEmpty()) {

                    log.debug("[分类:前置+后置] streamId:{}, 前置模型数:{}",
                            task.streamId, firstPush.getListNetPush().size());
                    processWithPrePost(task);
                    return;
                }

                // 情况2/3: 无前置,直接推理 (需要区分姿态和普通)
                log.debug("[分类:直接推理] streamId:{}, 模型数:{}",
                        task.streamId, task.netPushList.size());
                processDirectly(task);

            } catch (Exception e) {
                log.error("[任务处理失败] streamId:{}", task.streamId, e);
                task.future.complete(false);
                task.releaseFrame();
            }
        }

        /**
         * 情况1: 有前置模型的处理流程
         * 前置模型 -> 提取ROI -> 后置模型(可能是姿态/普通检测)
         */
        private void processWithPrePost(FullInferenceTask task) {
            List<NetPush> beforeList = task.netPushList.get(0).getListNetPush();

            if (beforeList == null || beforeList.isEmpty()) {
                log.warn("[前置列表为空] streamId:{}", task.streamId);
                task.future.complete(false);
                task.releaseFrame();
                return;
            }

            // 第一个是前置模型
            NetPush preModel = beforeList.get(0);
            task.claimFrame(); // 标记frame已被接管

            log.debug("[提交前置模型] streamId:{}, 模型:{}",
                    task.streamId, preModel.getTabAiModel().getAiName());

            // 提交前置模型任务
            CompletableFuture<retureBoxInfo> preFuture = submitPreModel(
                    task.streamId, task.frame, preModel, task.pushInfo
            );

            // 前置完成后,根据ROI结果提交后置模型
            preFuture.whenComplete((preResult, ex) -> {
                if (ex != null) {
                    log.error("[前置模型异常] streamId:{}", task.streamId, ex);
                    task.future.complete(false);
                    return;
                }

                if (preResult == null || !preResult.isFlag()) {
                    log.debug("[前置模型未检测到目标,跳过后置] streamId:{}", task.streamId);
                    task.future.complete(false);
                    return;
                }

                log.info("[前置模型检测成功] streamId:{}, ROI数量:{}",
                        task.streamId, preResult.getInfoList().size());

                // 提交后置模型(从索引1开始,因为0是前置)
                List<CompletableFuture<Boolean>> postFutures = new ArrayList<>();

                for (int i = 1; i < beforeList.size(); i++) {
                    NetPush postModel = beforeList.get(i);

                    // ========== 关键:根据DifyType判断后置类型 ==========
                    CompletableFuture<Boolean> postFuture;

                    if (postModel.getDifyType() == 2) {
                        // 类型3: 姿态识别 (Pose)
                        log.debug("[提交姿态后置] streamId:{}, 模型:{}",
                                task.streamId, postModel.getTabAiModel().getAiName());

                        postFuture = submitPoseModel(
                                task.streamId,
                                task.frame,
                                postModel,
                                task.pushInfo,
                                preResult.getInfoList()
                        );
                    } else {
                        // 类型2: 普通后置检测
                        log.debug("[提交普通后置] streamId:{}, 模型:{}",
                                task.streamId, postModel.getTabAiModel().getAiName());

                        postFuture = submitPostModel(
                                task.streamId,
                                task.frame,
                                postModel,
                                task.pushInfo,
                                preResult.getInfoList()
                        );
                    }

                    postFutures.add(postFuture);
                }

                // 等待所有后置模型完成
                CompletableFuture.allOf(postFutures.toArray(new CompletableFuture[0]))
                        .whenComplete((v, e) -> {
                            if (e != null) {
                                log.error("[后置模型批量异常] streamId:{}", task.streamId, e);
                                task.future.complete(false);
                                return;
                            }

                            boolean anySuccess = postFutures.stream()
                                    .anyMatch(f -> {
                                        try {
                                            return f.join();
                                        } catch (Exception exx) {
                                            exx.printStackTrace();
                                            return false;
                                        }
                                    });

                            log.debug("[前置+后置流程完成] streamId:{}, 成功:{}",
                                    task.streamId, anySuccess);
                            task.future.complete(anySuccess);
                        });
            });
        }

        /**
         * 情况2/3: 无前置,直接推理
         * 根据DifyType分为: 姿态识别(2) 或 普通检测(其他)
         */
        private void processDirectly(FullInferenceTask task) {
            task.claimFrame();
            List<CompletableFuture<Boolean>> futures = new ArrayList<>();

            for (NetPush netPush : task.netPushList) {
                CompletableFuture<Boolean> future;

                // ========== 关键:根据DifyType判断模型类型 ==========

                if (netPush.getDifyType() == 2) {
                    // 类型3: 批量姿态识别
                    log.debug("[提交姿态识别] streamId:{}, 模型:{}",
                            task.streamId, netPush.getTabAiModel().getAiName());

                    future = submitPoseModel(
                            task.streamId,
                            task.frame,
                            netPush,
                            task.pushInfo,
                            null  // 无前置结果
                    );
                } else {
                    // 类型1: 批量后置检测(无前置ROI)
                    log.debug("[提交普通后置] streamId:{}, 模型:{}",
                            task.streamId, netPush.getTabAiModel().getAiName());

                    future = submitPostModel(
                            task.streamId,
                            task.frame,
                            netPush,
                            task.pushInfo,
                            null  // 无前置结果
                    );
                }

                futures.add(future);
            }

            // 等待所有模型完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .whenComplete((v, e) -> {
                        if (e != null) {
                            log.error("[直接推理批量异常] streamId:{}", task.streamId, e);
                            task.future.complete(false);
                            return;
                        }

                        boolean anySuccess = futures.stream()
                                .anyMatch(f -> {
                                    try {
                                        return f.join();
                                    } catch (Exception ex) {
                                        return false;
                                    }
                                });

                        log.debug("[直接推理流程完成] streamId:{}, 成功:{}",
                                task.streamId, anySuccess);
                        task.future.complete(anySuccess);
                    });
        }
    }

// ============ 分类判断总结 ============

    /**
     * 三种模型处理流程:
     *
     * 1. 【批量后置】(类型1)
     *    - 特征: IsBefor != 0 或 ListNetPush为空
     *    - 特征: DifyType != 2
     *    - 流程: submitPostModel -> BatchOnnxInference.batchInferencePostModel
     *    - 示例: 安全帽检测、车辆检测、烟火检测
     *
     * 2. 【批量前置+后置+ROI】(类型2)
     *    - 特征: IsBefor = 0 且 ListNetPush不为空
     *    - 流程:
     *      a) submitPreModel -> BatchOnnxInference.batchInferencePreModel (提取ROI)
     *      b) submitPostModel -> BatchOnnxInference.batchInferencePostModel (基于ROI检测)
     *    - 示例: 先检测人,再检测人身上的安全帽
     *
     * 3. 【批量姿态识别】(类型3)
     *    - 特征: DifyType = 2
     *    - 流程: submitPoseModel -> BatchOnnxInference.batchInferencePoseModel
     *    - 示例: 跌倒检测、姿态异常检测
     */

    // ============ 前置模型处理 ============
    private CompletableFuture<retureBoxInfo> submitPreModel(
            String streamId, Mat frame, NetPush netPush,
            TabAiSubscriptionNew pushInfo) {

        String modelKey = getModelKey(netPush);
        BlockingQueue<PreModelTask> queue = preModelQueues.computeIfAbsent(
                modelKey, k -> new LinkedBlockingQueue<>(50)
        );

        Mat clonedFrame = new Mat();
        frame.copyTo(clonedFrame);

        PreModelTask task = new PreModelTask(streamId, clonedFrame, netPush, pushInfo);

        if (!queue.offer(task)) {
            log.warn("[前置队列已满] 模型:{}", modelKey);
            task.future.complete(null);
            task.cleanup();
        }

        return task.future;
    }

    private class PreModelWorker implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    processPreModelBatches();
                    Thread.sleep(5);
                } catch (Exception e) {
                    log.error("[前置模型工作线程异常]", e);
                }
            }
        }

        private void processPreModelBatches() throws InterruptedException {
            for (Map.Entry<String, BlockingQueue<PreModelTask>> entry : preModelQueues.entrySet()) {
                String modelKey = entry.getKey();
                BlockingQueue<PreModelTask> queue = entry.getValue();

                if (queue.isEmpty()) continue;

                List<PreModelTask> batch = collectBatch(queue, MAX_BATCH_SIZE);
                if (!batch.isEmpty()) {
                    processBatchInference(modelKey, batch, true);
                    totalBatches.incrementAndGet();
                }
            }
        }

        private void processBatchInference(String modelKey, List<PreModelTask> batch,
                                           boolean isPreModel) {
            try {
                // 准备批量数据
                List<Mat> mats = new ArrayList<>(batch.size());
                List<TabAiSubscriptionNew> pushInfos = new ArrayList<>(batch.size());

                for (PreModelTask task : batch) {
                    mats.add(task.frame);
                    pushInfos.add(task.pushInfo);
                }

                NetPush netPush = batch.get(0).netPush;

                // 真正的批量推理
                List<retureBoxInfo> results = BatchOnnxInference.batchInferencePreModel(
                        mats, netPush, pushInfos, redisTemplate
                );

                // 分发结果
                for (int i = 0; i < batch.size(); i++) {
                    batch.get(i).future.complete(results.get(i));
                    totalProcessed.incrementAndGet();
                }

                log.debug("[前置批次完成] 模型:{}, 批次大小:{}", modelKey, batch.size());

            } catch (Exception e) {
                log.error("[前置批次推理失败] 模型:{}", modelKey, e);
                batch.forEach(task -> {
                    task.future.complete(null);
                    totalFailed.incrementAndGet();
                });
            } finally {
                batch.forEach(PreModelTask::cleanup);
            }
        }
    }

    // ============ 后置模型处理 ============
    private CompletableFuture<Boolean> submitPostModel(
            String streamId, Mat frame, NetPush netPush,
            TabAiSubscriptionNew pushInfo, List<retureBoxInfo> preResults) {

        String modelKey = getModelKey(netPush);
        BlockingQueue<PostModelTask> queue = postModelQueues.computeIfAbsent(
                modelKey, k -> new LinkedBlockingQueue<>(50)
        );

        Mat clonedFrame = new Mat();
        frame.copyTo(clonedFrame);

        PostModelTask task = new PostModelTask(
                streamId, clonedFrame, netPush, pushInfo, preResults
        );

        if (!queue.offer(task)) {
            log.warn("[后置队列已满] 模型:{}", modelKey);
            task.future.complete(false);
            task.cleanup();
        }

        return task.future;
    }

    private class PostModelWorker implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    processPostModelBatches();
                    Thread.sleep(5);
                } catch (Exception e) {
                    log.error("[后置模型工作线程异常]", e);
                }
            }
        }

        private void processPostModelBatches() throws InterruptedException {
            for (Map.Entry<String, BlockingQueue<PostModelTask>> entry : postModelQueues.entrySet()) {
                String modelKey = entry.getKey();
                BlockingQueue<PostModelTask> queue = entry.getValue();

                if (queue.isEmpty()) continue;

                List<PostModelTask> batch = collectBatch(queue, MAX_BATCH_SIZE);
                if (!batch.isEmpty()) {
                    processPostBatch(modelKey, batch);
                    totalBatches.incrementAndGet();
                }
            }
        }

        private void processPostBatch(String modelKey, List<PostModelTask> batch) {
            try {
                List<Mat> mats = new ArrayList<>(batch.size());
                List<TabAiSubscriptionNew> pushInfos = new ArrayList<>(batch.size());
                List<List<retureBoxInfo>> preResultsList = new ArrayList<>(batch.size());

                for (PostModelTask task : batch) {
                    mats.add(task.frame);
                    pushInfos.add(task.pushInfo);
                    preResultsList.add(task.preResults);
                }

                NetPush netPush = batch.get(0).netPush;

                List<Boolean> results = BatchOnnxInference.batchInferencePostModel(
                        mats, netPush, pushInfos, preResultsList, redisTemplate
                );

                for (int i = 0; i < batch.size(); i++) {
                    batch.get(i).future.complete(results.get(i));
                    if (results.get(i)) {
                        totalProcessed.incrementAndGet();
                    } else {
                        totalFailed.incrementAndGet();
                    }
                }

                log.debug("[后置批次完成] 模型:{}, 批次大小:{}", modelKey, batch.size());

            } catch (Exception e) {
                log.error("[后置批次推理失败] 模型:{}", modelKey, e);
                batch.forEach(task -> {
                    task.future.complete(false);
                    totalFailed.incrementAndGet();
                });
            } finally {
                batch.forEach(PostModelTask::cleanup);
            }
        }
    }

    // ============ 姿态识别处理 ============
    private CompletableFuture<Boolean> submitPoseModel(
            String streamId, Mat frame, NetPush netPush,
            TabAiSubscriptionNew pushInfo, List<retureBoxInfo> preResults) {

        String modelKey = getModelKey(netPush);
        BlockingQueue<PoseModelTask> queue = poseModelQueues.computeIfAbsent(
                modelKey, k -> new LinkedBlockingQueue<>(50)
        );

        Mat clonedFrame = new Mat();
        frame.copyTo(clonedFrame);

        PoseModelTask task = new PoseModelTask(
                streamId, clonedFrame, netPush, pushInfo, preResults
        );

        if (!queue.offer(task)) {
            log.warn("[姿态队列已满] 模型:{}", modelKey);
            task.future.complete(false);
            task.cleanup();
        }

        return task.future;
    }

    private class PoseModelWorker implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    processPoseModelBatches();
                    Thread.sleep(10); // 姿态识别较慢,等待时间稍长
                } catch (Exception e) {
                    log.error("[姿态模型工作线程异常]", e);
                }
            }
        }

        private void processPoseModelBatches() throws InterruptedException {
            for (Map.Entry<String, BlockingQueue<PoseModelTask>> entry : poseModelQueues.entrySet()) {
                String modelKey = entry.getKey();
                BlockingQueue<PoseModelTask> queue = entry.getValue();

                if (queue.isEmpty()) continue;

                List<PoseModelTask> batch = collectBatch(queue, MAX_BATCH_SIZE);
                if (!batch.isEmpty()) {
                    processPoseBatch(modelKey, batch);
                    totalBatches.incrementAndGet();
                }
            }
        }

        private void processPoseBatch(String modelKey, List<PoseModelTask> batch) {
            try {
                List<Mat> mats = new ArrayList<>(batch.size());
                List<TabAiSubscriptionNew> pushInfos = new ArrayList<>(batch.size());
                List<List<retureBoxInfo>> preResultsList = new ArrayList<>(batch.size());

                for (PoseModelTask task : batch) {
                    mats.add(task.frame);
                    pushInfos.add(task.pushInfo);
                    preResultsList.add(task.preResults);
                }

                NetPush netPush = batch.get(0).netPush;

                // 批量姿态识别
                List<Boolean> results = BatchOnnxInference.batchInferencePoseModel(
                        mats, netPush, pushInfos, preResultsList, redisTemplate
                );

                for (int i = 0; i < batch.size(); i++) {
                    batch.get(i).future.complete(results.get(i));
                    if (results.get(i)) {
                        totalProcessed.incrementAndGet();
                    } else {
                        totalFailed.incrementAndGet();
                    }
                }

                log.debug("[姿态批次完成] 模型:{}, 批次大小:{}", modelKey, batch.size());

            } catch (Exception e) {
                log.error("[姿态批次推理失败] 模型:{}", modelKey, e);
                batch.forEach(task -> {
                    task.future.complete(false);
                    totalFailed.incrementAndGet();
                });
            } finally {
                batch.forEach(PoseModelTask::cleanup);
            }
        }
    }

    // ============ 辅助方法 ============

    /**
     * 通用批量收集方法
     */
    private <T extends BatchTask<?>> List<T> collectBatch(
            BlockingQueue<T> queue, int maxSize) throws InterruptedException {

        List<T> batch = new ArrayList<>(maxSize);
        T first = queue.poll();
        if (first == null) return batch;

        batch.add(first);

        long startTime = System.currentTimeMillis();
        while (batch.size() < maxSize) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= BATCH_WAIT_MS) break;

            T task = queue.poll(BATCH_WAIT_MS - elapsed, TimeUnit.MILLISECONDS);
            if (task == null) break;

            batch.add(task);
        }

        return batch;
    }

    private String getModelKey(NetPush netPush) {
        return netPush.getTabAiModel().getId() + "_" + netPush.getDifyType();
    }

    private void logStatistics() {
        if (!running) return;

        Map<String, Object> stats = getStatistics();
        log.info("[调度器统计] 主队列:{}, 前置队列:{}/{}, 后置队列:{}/{}, 姿态队列:{}/{}, " +
                        "已处理:{}, 失败:{}, 批次:{}",
                stats.get("mainQueued"),
                stats.get("preModels"), stats.get("preQueued"),
                stats.get("postModels"), stats.get("postQueued"),
                stats.get("poseModels"), stats.get("poseQueued"),
                stats.get("totalProcessed"),
                stats.get("totalFailed"),
                stats.get("totalBatches"));
    }

    // ============ 监控统计 ============
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("mainQueued", mainQueue.size());
        stats.put("preModels", preModelQueues.size());
        stats.put("preQueued", preModelQueues.values().stream()
                .mapToInt(BlockingQueue::size).sum());
        stats.put("postModels", postModelQueues.size());
        stats.put("postQueued", postModelQueues.values().stream()
                .mapToInt(BlockingQueue::size).sum());
        stats.put("poseModels", poseModelQueues.size());
        stats.put("poseQueued", poseModelQueues.values().stream()
                .mapToInt(BlockingQueue::size).sum());
        stats.put("totalProcessed", totalProcessed.get());
        stats.put("totalFailed", totalFailed.get());
        stats.put("totalBatches", totalBatches.get());

        return stats;
    }
}