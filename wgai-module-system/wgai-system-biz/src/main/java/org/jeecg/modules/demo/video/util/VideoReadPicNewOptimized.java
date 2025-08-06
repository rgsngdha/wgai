//package org.jeecg.modules.demo.video.util;
//
//import lombok.extern.slf4j.Slf4j;
//import org.bytedeco.ffmpeg.global.avutil;
//import org.bytedeco.javacv.*;
//import org.bytedeco.javacv.Frame;
//import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
//import org.jeecg.modules.demo.video.util.frame.FrameQualityFilter;
//import org.jeecg.modules.tab.AIModel.NetPush;
//import org.opencv.core.Mat;
//import org.springframework.data.redis.core.RedisTemplate;
//
//import java.awt.image.BufferedImage;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicInteger;
//import java.util.concurrent.atomic.AtomicLong;
//
//import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;
//
///**
// * 优化后的视频读取类 - 集成质量过滤
// * @author wggg
// * @date 2025/5/20 17:41
// */
//@Slf4j
//public class VideoReadPicNewOptimized implements Runnable {
//
//    private static final ThreadLocal<TabAiSubscriptionNew> threadLocalPushInfo = new ThreadLocal<>();
//    ThreadLocal<identifyTypeNew> identifyTypeNewLocal = ThreadLocal.withInitial(identifyTypeNew::new);
//    TabAiSubscriptionNew tabAiSubscriptionNew;
//
//    // 共享资源
//    private static final ExecutorService SHARED_EXECUTOR = Executors.newFixedThreadPool(
//            Runtime.getRuntime().availableProcessors() * 2);
//    private static volatile Java2DFrameConverter SHARED_CONVERTER;
//
//    // 内存池
//    private final BlockingQueue<Mat> matPool = new LinkedBlockingQueue<>(50);
//    private final BlockingQueue<BufferedImage> imagePool = new LinkedBlockingQueue<>(50);
//    private final AtomicInteger processingCount = new AtomicInteger(0);
//    private static final int MAX_CONCURRENT_PROCESSING = 16;
//
//    // 性能和质量监控
//    private volatile long lastLogTime = 0;
//    private final AtomicLong processedFrames = new AtomicLong(0);
//    private final AtomicLong droppedFrames = new AtomicLong(0);
//    private final AtomicLong mosaicFrames = new AtomicLong(0);
//    private final AtomicLong grayFrames = new AtomicLong(0);
//    private final AtomicLong blurFrames = new AtomicLong(0);
//
//    // 质量控制参数
//    private static final double MIN_QUALITY_SCORE = 60.0; // 最低质量分数
//    private static final boolean ENABLE_STRICT_FILTERING = true; // 启用严格过滤
//
//    RedisTemplate redisTemplate;
//
//    public VideoReadPicNewOptimized(TabAiSubscriptionNew tabAiSubscriptionNew, RedisTemplate redisTemplate) {
//        this.tabAiSubscriptionNew = tabAiSubscriptionNew;
//        this.redisTemplate = redisTemplate;
//    }
//
//    @Override
//    public void run() {
//        threadLocalPushInfo.set(tabAiSubscriptionNew);
//        tabAiSubscriptionNew = threadLocalPushInfo.get();
//
//        List<NetPush> netPushList = tabAiSubscriptionNew.getNetPushList();
//
//        if (tabAiSubscriptionNew.getPyType().equals("5")) {
//            FFmpegFrameGrabber grabber = null;
//            try {
//                grabber = createOptimizedGrabber();
//                identifyTypeNew identifyTypeAll = identifyTypeNewLocal.get();
//
//                Frame frame;
//                long lastTimestamp = 0;
//                long intervalMicros = 1000_000; // 1秒间隔
//                int frameSkipCounter = 0;
//                int consecutiveNullFrames = 0;
//                int consecutiveBadQualityFrames = 0; // 连续低质量帧计数
//
//                log.info("[视频流启动] {} 质量过滤: {}",
//                        tabAiSubscriptionNew.getName(), ENABLE_STRICT_FILTERING ? "启用" : "禁用");
//
//                while (true) {
//                    if (!isStreamActive()) {
//                        log.warn("[结束推送] {}", tabAiSubscriptionNew.getName());
//                        break;
//                    }
//
//                    frame = grabber.grabImage();
//                    if (frame == null) {
//                        consecutiveNullFrames++;
//                        if (consecutiveNullFrames > 10) {
//                            log.info("[连续空帧过多，重启视频流]");
//                            grabber = restartGrabber(grabber);
//                            consecutiveNullFrames = 0;
//                        }
//                        Thread.sleep(2000);
//                        continue;
//                    }
//                    consecutiveNullFrames = 0;
//
//                    // 时间间隔控制
//                    long timestamp = grabber.getTimestamp();
//                    if (!shouldProcessFrame(timestamp, lastTimestamp, intervalMicros, frameSkipCounter)) {
//                        frame.close();
//                        frameSkipCounter++;
//                        continue;
//                    }
//                    lastTimestamp = timestamp;
//                    frameSkipCounter = 0;
//
//                    // 背压控制
//                    if (processingCount.get() >= MAX_CONCURRENT_PROCESSING) {
//                        frame.close();
//                        droppedFrames.incrementAndGet();
//                        continue;
//                    }
//
//                    // 异步处理帧（包含质量检测）
//                    boolean processed = processFrameWithQualityCheck(frame, netPushList, identifyTypeAll);
//
//                    if (processed) {
//                        processedFrames.incrementAndGet();
//                        consecutiveBadQualityFrames = 0;
//                    } else {
//                        consecutiveBadQualityFrames++;
//
//                        // 连续低质量帧过多时，可能需要调整摄像头参数
//                        if (consecutiveBadQualityFrames > 50) {
//                            log.warn("[连续{}帧质量不佳，请检查摄像头设置]", consecutiveBadQualityFrames);
//                            consecutiveBadQualityFrames = 0; // 重置计数器
//                        }
//                    }
//
//                    // 性能统计
//                    logDetailedPerformanceStats();
//                }
//
//            } catch (Exception exception) {
//                log.error("[处理异常]", exception);
//            } finally {
//                cleanup(grabber);
//            }
//        }
//    }
//
//    /**
//     * 带质量检测的帧处理方法
//     */
//    private boolean processFrameWithQualityCheck(Frame frame, List<NetPush> netPushList,
//                                                 identifyTypeNew identifyTypeAll) {
//        if (frame == null) {
//            return false;
//        }
//
//        processingCount.incrementAndGet();
//
//        // 提交异步任务
//        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
//            BufferedImage image = null;
//            Mat matInfo = null;
//            long startTime = System.currentTimeMillis();
//
//            try {
//                // 转换Frame为BufferedImage
//                Java2DFrameConverter converter = getConverter();
//                if (converter == null) {
//                    log.error("[转换器初始化失败]");
//                    return false;
//                }
//
//                if (frame.image == null && frame.samples == null) {
//                    return false;
//                }
//
//                try {
//                    image = converter.getBufferedImage(frame);
//                } catch (Exception e) {
//                    log.debug("[Frame转换异常]: {}", e.getMessage());
//                    return false;
//                }
//
//                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
//                    return false;
//                }
//
//                // 转换为Mat
//                matInfo = bufferedImageToMat(image);
//                if (matInfo == null || matInfo.empty()) {
//                    return false;
//                }
//
//                // ============ 质量检测核心逻辑 ============
//                boolean isHighQuality = false;
//                double qualityScore = 0;
//
//                if (ENABLE_STRICT_FILTERING) {
//                    // 严格模式：完整质量检测
//                    isHighQuality = FrameQualityFilter.isHighQualityFrame(matInfo, image);
//                    qualityScore = FrameQualityFilter.getFrameQualityScore(matInfo);
//
//                    // 记录不同类型的质量问题
//                    if (!isHighQuality) {
//                        if (FrameQualityFilter.isGrayFrame(matInfo)) {
//                            grayFrames.incrementAndGet();
//                        }
//                        if (FrameQualityFilter.isMosaicFrame(matInfo)) {
//                            mosaicFrames.incrementAndGet();
//                        }
//                        if (FrameQualityFilter.isBlurryFrame(matInfo)) {
//                            blurFrames.incrementAndGet();
//                        }
//                    }
//
//                } else {
//                    // 快速模式：基础质量检测
//                    isHighQuality = FrameQualityFilter.isHighQualityFrameFast(matInfo);
//                    qualityScore = isHighQuality ? 80.0 : 40.0; // 简化评分
//                }
//
//                // 质量不达标，直接返回
//                if (!isHighQuality || qualityScore < MIN_QUALITY_SCORE) {
//                    if (log.isDebugEnabled()) {
//                        log.debug("[低质量帧过滤] 评分: {:.1f}, 尺寸: {}x{}",
//                                qualityScore, image.getWidth(), image.getHeight());
//                    }
//                    droppedFrames.incrementAndGet();
//                    return false;
//                }
//                // ========================================
//
//                // 高质量帧：执行AI推理
//                final Mat sourceMat = matInfo;
//                final int netPushCount = netPushList.size();
//
//                log.info("[高质量帧处理] 评分: {:.1f}, 尺寸: {}x{}, 推送数量: {}",
//                        qualityScore, image.getWidth(), image.getHeight(), netPushCount);
//
//                // 处理推理任务
//                if (netPushCount == 1) {
//                    Mat mat = getMat();
//                    try {
//                        sourceMat.copyTo(mat);
//                        processNetPush(mat, netPushList.get(0), identifyTypeAll, tabAiSubscriptionNew.getName());
//                    } finally {
//                        returnMat(mat);
//                    }
//                } else {
//                    // 并行处理多个推送
//                    List<CompletableFuture<Void>> futures = new ArrayList<>(netPushCount);
//                    for (NetPush netPush : netPushList) {
//                        CompletableFuture<Void> taskFuture = CompletableFuture.runAsync(() -> {
//                            Mat mat = getMat();
//                            try {
//                                sourceMat.copyTo(mat);
//                                processNetPush(mat, netPush, identifyTypeAll, tabAiSubscriptionNew.getName());
//                            } finally {
//                                returnMat(mat);
//                            }
//                        }, SHARED_EXECUTOR);
//                        futures.add(taskFuture);
//                    }
//
//                    try {
//                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
//                                .get(15, TimeUnit.SECONDS);
//                    } catch (TimeoutException e) {
//                        log.warn("[推理超时，取消剩余任务]");
//                        futures.forEach(f -> f.cancel(true));
//                    }
//                }
//
//                long processTime = System.currentTimeMillis() - startTime;
//                if (processTime > 2000) {
//                    log.warn("[帧处理耗时: {}ms, 质量评分: {:.1f}]", processTime, qualityScore);
//                }
//
//                return true;
//
//            } catch (Exception e) {
//                log.error("[质量检测处理异常]", e);
//                return false;
//            } finally {
//                // 资源清理
//                if (matInfo != null) {
//                    returnMat(matInfo);
//                }
//                if (image != null) {
//                    returnBufferedImage(image);
//                }
//            }
//        }, SHARED_EXECUTOR);
//
//        // 异步执行，立即返回
//        future.whenComplete((result, throwable) -> {
//            if (frame != null) {
//                frame.close();
//            }
//            processingCount.decrementAndGet();
//        });
//
//        return true; // 表示任务已提交
//    }
//
//    /**
//     * 详细性能统计日志
//     */
//    private void logDetailedPerformanceStats() {
//        long currentTime = System.currentTimeMillis();
//        if (currentTime - lastLogTime > 30000) {
//            long processed = processedFrames.get();
//            long dropped = droppedFrames.get();
//            long mosaic = mosaicFrames.get();
//            long gray = grayFrames.get();
//            long blur = blurFrames.get();
//            int currentProcessing = processingCount.get();
//            long total = processed + dropped;
//
//            if (total > 0) {
//                log.info("[详细性能统计] 总帧数: {}, 处理: {}({:.1f}%), 过滤: {}({:.1f}%), " +
//                                "当前处理: {}, 马赛克: {}, 全灰: {}, 模糊: {}",
//                        total, processed, processed * 100.0 / total,
//                        dropped, dropped * 100.0 / total,
//                        currentProcessing, mosaic, gray, blur);
//            }
//
//            lastLogTime = currentTime;
//
//            // 重置计数器防止溢出
//            if (total > 50000) {
//                processedFrames.set(0);
//                droppedFrames.set(0);
//                mosaicFrames.set(0);
//                grayFrames.set(0);
//                blurFrames.set(0);
//            }
//        }
//    }
//
//    /**
//     * 优化的grabber创建方法
//     */
//    private FFmpegFrameGrabber createOptimizedGrabber() throws Exception {
//        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(tabAiSubscriptionNew.getBeginEventTypes());
//
//        // 基本设置
//        grabber.setOption("loglevel", "-8");
//        grabber.setOption("rtsp_transport", "tcp");
//        grabber.setOption("stimeout", "5000000");
//
//        // 关键：像素格式设置，避免马赛克
//        grabber.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
//
//        // GPU加速
//        if ("1".equals(tabAiSubscriptionNew.getEventTypes())) {
//            grabber.setOption("hwaccel", "cuda");
//            grabber.setOption("hwaccel_device", "0");
//            log.info("[启用CUDA GPU加速]");
//        }
//
//        // 质量优化设置
//        grabber.setOption("buffer_size", "2097152");
//        grabber.setOption("reconnect", "1");
//        grabber.setOption("skip_frame", "none");
//        grabber.setOption("threads", "auto");
//        grabber.setOption("err_detect", "ignore_err");
//        grabber.setOption("an", "1"); // 禁用音频
//
//        grabber.start();
//        return grabber;
//    }
//
//    // 其他辅助方法保持不变...
//    private static Java2DFrameConverter getConverter() {
//        if (SHARED_CONVERTER == null) {
//            synchronized (VideoReadPicNewOptimized.class) {
//                if (SHARED_CONVERTER == null) {
//                    SHARED_CONVERTER = new Java2DFrameConverter();
//                }
//            }
//        }
//        return SHARED_CONVERTER;
//    }
//
//    private Mat getMat() {
//        Mat mat = matPool.poll();
//        return mat != null ? mat : new Mat();
//    }
//
//    private void returnMat(Mat mat) {
//        if (mat != null && !mat.empty()) {
//            if (matPool.size() < 50) {
//                matPool.offer(mat);
//            } else {
//                mat.release();
//            }
//        }
//    }
//
//    private BufferedImage getBufferedImage(int width, int height, int type) {
//        BufferedImage image = imagePool.poll();
//        if (image != null && image.getWidth() == width &&
//                image.getHeight() == height && image.getType() == type) {
//            return image;
//        }
//        return new BufferedImage(width, height, type);
//    }
//
//    private void returnBufferedImage(BufferedImage image) {
//        if (image != null && imagePool.size() < 20) {
//            imagePool.offer(image);
//        }
//    }
//
//    private boolean isStreamActive() {
//        try {
//            return RedisCacheHolder.get(tabAiSubscriptionNew.getId() + "newRunPush");
//        } catch (Exception e) {
//            log.warn("[检查流状态异常]", e);
//            return false;
//        }
//    }
//
//    private boolean shouldProcessFrame(long timestamp, long lastTimestamp,
//                                       long intervalMicros, int frameSkipCounter) {
//        if (timestamp - lastTimestamp < intervalMicros) {
//            return false;
//        }
//        if (isSystemUnderPressure()) {
//            return frameSkipCounter % 3 == 0;
//        }
//        return true;
//    }
//
//    private boolean isSystemUnderPressure() {
//        Runtime runtime = Runtime.getRuntime();
//        double memoryUsage = (double)(runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
//        return memoryUsage > 0.85 || processingCount.get() > MAX_CONCURRENT_PROCESSING * 0.85;
//    }
//
//    private FFmpegFrameGrabber restartGrabber(FFmpegFrameGrabber grabber) throws Exception {
//        grabber.stop();
//        grabber.release();
//        Thread.sleep(1000); // 等待释放完成
//        return createOptimizedGrabber();
//    }
//
//    private void processNetPush(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll, String name) {
//        // 保持原有逻辑不变
//        try {
//            if (netPush.getIsBefor() == 1) {
//                processWithPredecessors(mat, netPush, identifyTypeAll);
//            } else {
//                processWithoutPredecessors(mat, netPush, identifyTypeAll);
//            }
//        } catch (Exception e) {
//            log.error("[处理NetPush异常] 模型: {}",
//                    netPush.getTabAiModel() != null ? netPush.getTabAiModel().getAiName() : "unknown", e);
//        }
//    }
//
//    private void processWithoutPredecessors(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll) {
//        executeDetection(mat, netPush, identifyTypeAll);
//    }
//
//    private void executeDetection(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll) {
//        try {
//            if ("1".equals(netPush.getModelType())) {
//                identifyTypeAll.detectObjectsDify(tabAiSubscriptionNew, mat, netPush, redisTemplate);
//            } else {
//                identifyTypeAll.detectObjectsDifyV5(tabAiSubscriptionNew, mat, netPush, redisTemplate);
//            }
//        } catch (Exception e) {
//            log.error("[执行检测异常] 模型类型: {}", netPush.getModelType(), e);
//        }
//    }
//
//    private void processWithPredecessors(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll) {
//        // 保持原有前置处理逻辑
//        List<NetPush> before = netPush.getListNetPush();
//        if (before == null || before.isEmpty()) {
//            return;
//        }
//
//        boolean validationPassed = true;
//        for (int i = 0; i < before.size() && validationPassed; i++) {
//            NetPush beforePush = before.get(i);
//            if (i == 0) {
//                validationPassed = validateFirstModel(mat, beforePush, identifyTypeAll);
//                if (!validationPassed) break;
//            }
//            executeDetection(mat, beforePush, identifyTypeAll);
//        }
//    }
//
//    private boolean validateFirstModel(Mat mat, NetPush beforePush, identifyTypeNew identifyTypeAll) {
//        try {
//            if ("1".equals(beforePush.getModelType())) {
//                return identifyTypeAll.detectObjects(tabAiSubscriptionNew, mat, beforePush.getNet(),
//                        beforePush.getClaseeNames(), beforePush);
//            } else {
//                return identifyTypeAll.detectObjectsV5(tabAiSubscriptionNew, mat, beforePush.getNet(),
//                        beforePush.getClaseeNames(), beforePush);
//            }
//        } catch (Exception e) {
//            log.error("[验证模型异常]", e);
//            return false;
//        }
//    }
//
//    private void cleanup(FFmpegFrameGrabber grabber) {
//        log.info("[开始清理资源]");
//        if (grabber != null) {
//            try {
//                grabber.stop();
//                grabber.release();
//            } catch (Exception e) {
//                log.error("[释放grabber异常]", e);
//            }
//        }
//
//        // 清理对象池
//        matPool.clear();
//        imagePool.clear();
//
//        log.info("[资源清理完成]");
//    }
//}