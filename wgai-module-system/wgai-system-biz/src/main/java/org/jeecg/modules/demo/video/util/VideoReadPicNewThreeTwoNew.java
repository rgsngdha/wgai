package org.jeecg.modules.demo.video.util;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.opencv.core.Mat;
import org.springframework.data.redis.core.RedisTemplate;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;

/**
 * 极致内存优化版视频处理器 - 解决32路摄像头OOM问题
 *
 * 核心优化策略：
 * 1. 严格的内存控制和限制
 * 2. 智能的资源池管理
 * 3. 动态帧率和负载调整
 * 4. 多级内存检查和清理
 * 5. 更保守的资源分配策略
 */
@Slf4j
public class VideoReadPicNewThreeTwoNew implements Runnable {

    // ================== 全局配置 - 针对32路优化 ==================

    // 更保守的线程池配置
    private static final int CORE_POOL_SIZE = 8;  // 固定核心线程数
    private static final int MAX_POOL_SIZE = 16;  // 最大线程数严格限制
    private static final int QUEUE_CAPACITY = 64; // 更小的队列容量

    // 严格的对象池限制
    private static final int MAX_CONVERTER_POOL_SIZE = 16; // 减少转换器池大小
    private static final int MAX_MAT_POOL_SIZE = 32;      // 减少Mat池大小
    private static final int MAX_IMAGE_POOL_SIZE = 16;    // 减少图像池大小

    // 更严格的内存阈值
    private static final double MEMORY_WARNING_THRESHOLD = 0.65;  // 降低到65%
    private static final double MEMORY_CRITICAL_THRESHOLD = 0.75; // 75%进入紧急状态
    private static final double MEMORY_EMERGENCY_THRESHOLD = 0.85;// 85%强制清理
    private static final long MEMORY_CHECK_INTERVAL = 2000;       // 2秒检查一次

    // 帧处理限制
    private static final int MAX_CONCURRENT_FRAMES_PER_STREAM = 1; // 每个流最多1帧排队
    private static final long MIN_FRAME_INTERVAL = 2000;          // 最小2秒间隔
    private static final long MAX_PROCESSING_TIME = 5000;         // 最大处理时间3秒

    // ================== 全局共享资源 ==================

    private static volatile ExecutorService GLOBAL_EXECUTOR;
    private static final Object EXECUTOR_LOCK = new Object();

    // 严格限制的对象池
    private static final BlockingQueue<Java2DFrameConverter> CONVERTER_POOL =
            new ArrayBlockingQueue<>(MAX_CONVERTER_POOL_SIZE);
    private static final BlockingQueue<Mat> MAT_POOL =
            new ArrayBlockingQueue<>(MAX_MAT_POOL_SIZE);
    private static final BlockingQueue<BufferedImage> IMAGE_POOL =
            new ArrayBlockingQueue<>(MAX_IMAGE_POOL_SIZE);

    // 全局监控
    private static final AtomicInteger ACTIVE_STREAMS = new AtomicInteger(0);
    private static final AtomicLong TOTAL_PROCESSED_FRAMES = new AtomicLong(0);
    private static final AtomicLong TOTAL_DROPPED_FRAMES = new AtomicLong(0);
    private static final AtomicInteger CURRENT_PROCESSING_FRAMES = new AtomicInteger(0);

    private static volatile long LAST_GLOBAL_MEMORY_CHECK = 0;
    private static volatile long LAST_GLOBAL_GC = 0;

    // 内存压力控制
    private static final AtomicBoolean GLOBAL_MEMORY_PRESSURE = new AtomicBoolean(false);
    private static final AtomicBoolean GLOBAL_EMERGENCY_MODE = new AtomicBoolean(false);

    // ================== 实例属性 ==================

    private final TabAiSubscriptionNew tabAiSubscriptionNew;
    private final RedisTemplate redisTemplate;
    private final String streamId;

    // 实例状态控制
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger pendingFrames = new AtomicInteger(0);
    private final AtomicLong processedFrames = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);

    // 动态帧率控制
    private volatile long currentFrameInterval = MIN_FRAME_INTERVAL;
    private volatile long lastFrameTime = 0;
    private volatile long lastProcessTime = System.currentTimeMillis();

    // ThreadLocal资源
    private final ThreadLocal<identifyTypeNew> identifyTypeLocal =
            ThreadLocal.withInitial(identifyTypeNew::new);

    // 本地小缓存池
    private final Queue<Mat> localMatCache = new ArrayDeque<>(2);

    static {
        // JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.warn("[JVM关闭，清理所有资源]");
            shutdownGlobalResources();
        }, "VideoProcessor-Shutdown"));

        // 定期内存检查线程
        startGlobalMemoryMonitor();

        // 设置OpenCV线程限制
        try {
            org.opencv.core.Core.setNumThreads(1);
        } catch (Exception e) {
            log.warn("[设置OpenCV线程数失败]", e);
        }
    }

    public VideoReadPicNewThreeTwoNew(TabAiSubscriptionNew tabAiSubscriptionNew,
                                      RedisTemplate redisTemplate) {
        this.tabAiSubscriptionNew = tabAiSubscriptionNew;
        this.redisTemplate = redisTemplate;
        this.streamId = tabAiSubscriptionNew.getId();

        int activeCount = ACTIVE_STREAMS.incrementAndGet();

        // 根据活跃流数量动态调整帧间隔
        adjustFrameIntervalByLoad(activeCount);

        log.info("[创建视频处理器] 流: {}, 活跃流数: {}, 初始帧间隔: {}ms",
                tabAiSubscriptionNew.getName(), activeCount, currentFrameInterval);
    }

    @Override
    public void run() {
        FFmpegFrameGrabber grabber = null;

        try {
            // 预检查内存状态
            if (!checkGlobalMemoryState()) {
                log.error("[内存不足，拒绝启动] 流: {}", tabAiSubscriptionNew.getName());
                return;
            }

            grabber = createMemoryOptimizedGrabber();
            identifyTypeNew identifyType = identifyTypeLocal.get();
            List<NetPush> netPushList = tabAiSubscriptionNew.getNetPushList();

            int consecutiveErrors = 0;
            int consecutiveNullFrames = 0;
            long lastStatsLog = System.currentTimeMillis();

            log.info("[开始处理视频流] 流: {}, 帧间隔: {}ms",
                    tabAiSubscriptionNew.getName(), currentFrameInterval);

            while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 全局状态检查
                    if (!performPreFrameChecks()) {
                        Thread.sleep(1000);
                        continue;
                    }

                    // 获取帧
                    Frame frame = grabber.grabImage();
                    if (frame == null) {
                        consecutiveNullFrames++;
                        if (consecutiveNullFrames > 3) {
                            log.warn("[连续空帧，重启采集器] 流: {}", tabAiSubscriptionNew.getName());
                            grabber = restartGrabber(grabber);
                            consecutiveNullFrames = 0;
                        }
                        Thread.sleep(500);
                        continue;
                    }

                    consecutiveNullFrames = 0;
                    consecutiveErrors = 0;

                    // 严格的帧率控制
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastFrameTime < currentFrameInterval) {
                        frame.close();
                        continue;
                    }

                    // 检查排队帧数
                    if (pendingFrames.get() >= MAX_CONCURRENT_FRAMES_PER_STREAM) {
                        frame.close();
                        droppedFrames.incrementAndGet();
                        TOTAL_DROPPED_FRAMES.incrementAndGet();
                        adjustFrameIntervalUp();
                        continue;
                    }

                    lastFrameTime = currentTime;

                    // 异步处理帧
                    if (submitFrameProcessing(frame, netPushList, identifyType)) {
                        processedFrames.incrementAndGet();
                        TOTAL_PROCESSED_FRAMES.incrementAndGet();
                    }

                    // 定期统计日志
                    if (currentTime - lastStatsLog > 60000) {
                        logStats();
                        lastStatsLog = currentTime;
                    }

                } catch (Exception e) {
                    consecutiveErrors++;
                    log.error("[帧处理异常] 流: {}, 连续错误: {}",
                            tabAiSubscriptionNew.getName(), consecutiveErrors, e);

                    if (consecutiveErrors > 5) {
                        log.error("[连续错误过多，停止处理] 流: {}", tabAiSubscriptionNew.getName());
                        break;
                    }

                    Thread.sleep(1000);
                }
            }

        } catch (OutOfMemoryError oom) {
            log.error("[严重OOM] 流: {}", tabAiSubscriptionNew.getName(), oom);
            performEmergencyCleanup();
        } catch (Exception e) {
            log.error("[视频处理异常] 流: {}", tabAiSubscriptionNew.getName(), e);
        } finally {
            cleanup(grabber);
        }
    }

    /**
     * 创建内存优化的采集器
     */
    private FFmpegFrameGrabber createMemoryOptimizedGrabber() throws Exception {
        // 第一步：先探测流信息
        FFmpegFrameGrabber probe = new FFmpegFrameGrabber(tabAiSubscriptionNew.getBeginEventTypes());
        probe.setOption("rtsp_transport", "tcp");
        probe.setOption("stimeout", "5000000");
        probe.start();
        String codecName = probe.getVideoCodecName();
        int codecId = probe.getVideoCodec();
        probe.stop();
        probe.close();
        probe.release();
        log.info(" 检测到视频编码: " + codecName + " (ID=" + codecId + ")");
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(tabAiSubscriptionNew.getBeginEventTypes());

        // GPU设置
        if (tabAiSubscriptionNew.getEventTypes().equals("1")) {


            grabber.setOption("hwaccel", "cuda");
            grabber.setOption("hwaccel_device", "0");
            grabber.setOption("hwaccel_output_format", "cuda");
            log.info("[使用GPU_CUDA加速解码]");
        }else if(tabAiSubscriptionNew.getEventTypes().equals("4")){
            //intel 加速
            grabber.setOption("hwaccel", "qsv");          // Intel QuickSync
            //grabber.setVideoCodecName("hevc_qsv");         // H.265 QSV
            if ("h264".equalsIgnoreCase(codecName)) {
                grabber.setVideoCodecName("h264_qsv");
            } else if ("hevc".equalsIgnoreCase(codecName) || "hevc1".equalsIgnoreCase(codecName)) {
                grabber.setVideoCodecName("hevc_qsv");
            }
            log.info("[使用Intel加速解码]");
        }
        // 基础设置
        grabber.setOption("loglevel", "-8");
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");
        grabber.setOption("stimeout", "3000000");

// 只解码关键帧
        grabber.setOption("skip_frame", "nokey"); // 跳过非关键帧，只保留 keyframe
        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);

        // 实时流优化
        grabber.setOption("flags", "low_delay");
        grabber.setOption("max_delay", "500000");
        grabber.setOption("buffer_size", "512000"); // 减小缓冲区
        grabber.setOption("fflags", "nobuffer+flush_packets+discardcorrupt");
        grabber.setOption("flags", "low_delay");
        grabber.setOption("flags2", "fast");
        grabber.setOption("err_detect", "compliant");   // 严格错误检测
        grabber.setOption("framedrop", "1");

        // 严格的实时设置
        grabber.setFrameRate(2.0); // 2fps
        grabber.setOption("r", "2"); // 输入帧率限制

        grabber.start();
        return grabber;
    }

    /**
     * 帧处理前的检查
     */
    private boolean performPreFrameChecks() {
        // 1. 检查全局内存状态
        if (GLOBAL_EMERGENCY_MODE.get()) {
            return false;
        }

        // 2. 检查流状态
        if (!isStreamActive()) {
            return false;
        }

        // 3. 检查当前处理负载
        if (CURRENT_PROCESSING_FRAMES.get() > MAX_POOL_SIZE * 2) {
            return false;
        }

        // 4. 定期内存检查
        checkMemoryPeriodically();

        return true;
    }

    /**
     * 提交帧处理任务
     */
    private boolean submitFrameProcessing(Frame frame, List<NetPush> netPushList,
                                          identifyTypeNew identifyType) {
        ExecutorService executor = getGlobalExecutor();
        if (executor == null || executor.isShutdown()) {
            frame.close();
            return false;
        }

        // 创建帧副本
        Frame frameClone;
        try {
            frameClone = frame.clone();
            frame.close(); // 立即释放原始帧
        } catch (Exception e) {
            log.error("[帧克隆失败] 流: {}", tabAiSubscriptionNew.getName(), e);
            frame.close();
            return false;
        }

        pendingFrames.incrementAndGet();
        CURRENT_PROCESSING_FRAMES.incrementAndGet();

        // 提交处理任务
        try {
            executor.submit(() -> processFrameSafely(frameClone, netPushList, identifyType));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("[任务被拒绝] 流: {}", tabAiSubscriptionNew.getName(), e);
            frameClone.close();
            pendingFrames.decrementAndGet();
            CURRENT_PROCESSING_FRAMES.decrementAndGet();
            return false;
        }
    }

    /**
     * 安全的帧处理
     */
    private void processFrameSafely(Frame frame, List<NetPush> netPushList,
                                    identifyTypeNew identifyType) {
        long startTime = System.currentTimeMillis();
        Java2DFrameConverter converter = null;
        Mat matInfo = null;

        try {
            // 超时检查
            if (stopped.get() || GLOBAL_EMERGENCY_MODE.get()) {
                return;
            }

            // 获取转换器
            converter = borrowConverter();
            if (converter == null) {
                log.debug("[无法获取转换器] 流: {}", tabAiSubscriptionNew.getName());
                return;
            }

            // 转换为BufferedImage
            BufferedImage image = converter.getBufferedImage(frame);
            if (image == null || image.getWidth() <= 0) {
                return;
            }

            // 获取Mat
            matInfo = borrowMat();
            if (matInfo == null) {
                log.debug("[无法获取Mat] 流: {}", tabAiSubscriptionNew.getName());
                return;
            }

            // 图像转换
            Mat tempMat = bufferedImageToMat(image);
            if (tempMat != null && !tempMat.empty()) {
                tempMat.copyTo(matInfo);
                tempMat.release();
            } else {
                return;
            }

            // 处理推理
            for (NetPush netPush : netPushList) {
                // 超时和状态检查
                if (stopped.get() || GLOBAL_EMERGENCY_MODE.get() ||
                        (System.currentTimeMillis() - startTime) > MAX_PROCESSING_TIME) {
                    break;
                }

                processNetPush(matInfo, netPush, identifyType);
            }

            lastProcessTime = System.currentTimeMillis();

        } catch (OutOfMemoryError oom) {
            log.error("[帧处理OOM] 流: {}", tabAiSubscriptionNew.getName(), oom);
            triggerEmergencyCleanup();
        } catch (Exception e) {
            log.error("[帧处理异常] 流: {}", tabAiSubscriptionNew.getName(), e);
        } finally {
            // 资源清理
            if (matInfo != null) {
                returnMat(matInfo);
            }
            if (converter != null) {
                returnConverter(converter);
            }
            if (frame != null) {
                frame.close();
            }

            pendingFrames.decrementAndGet();
            CURRENT_PROCESSING_FRAMES.decrementAndGet();
        }
    }

    /**
     * 获取全局执行器
     */
    private static ExecutorService getGlobalExecutor() {
        if (GLOBAL_EXECUTOR == null || GLOBAL_EXECUTOR.isShutdown()) {
            synchronized (EXECUTOR_LOCK) {
                if (GLOBAL_EXECUTOR == null || GLOBAL_EXECUTOR.isShutdown()) {
                    GLOBAL_EXECUTOR = new ThreadPoolExecutor(
                            CORE_POOL_SIZE, MAX_POOL_SIZE,
                            60L, TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                            r -> {
                                Thread t = new Thread(r, "VideoGlobal-" + System.nanoTime());
                                t.setDaemon(true);
                                t.setPriority(Thread.NORM_PRIORITY - 1);
                                return t;
                            },
                            new ThreadPoolExecutor.DiscardOldestPolicy()
                    );

                    log.info("[创建全局线程池] 核心: {}, 最大: {}, 队列: {}",
                            CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);
                }
            }
        }
        return GLOBAL_EXECUTOR;
    }

    /**
     * 借用转换器
     */
    private Java2DFrameConverter borrowConverter() {
        Java2DFrameConverter converter = CONVERTER_POOL.poll();
        if (converter != null) {
            return converter;
        }

        try {
            return new Java2DFrameConverter();
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * 归还转换器
     */
    private void returnConverter(Java2DFrameConverter converter) {
        if (converter != null && CONVERTER_POOL.size() < MAX_CONVERTER_POOL_SIZE) {
            CONVERTER_POOL.offer(converter);
        }
    }

    /**
     * 借用Mat
     */
    private Mat borrowMat() {
        // 先从本地缓存获取
        Mat mat = localMatCache.poll();
        if (mat != null && !mat.empty()) {
            return mat;
        }

        // 从全局池获取
        mat = MAT_POOL.poll();
        if (mat != null && !mat.empty()) {
            return mat;
        }

        // 创建新的
        try {
            return new Mat();
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * 归还Mat
     */
    private void returnMat(Mat mat) {
        if (mat != null && !mat.empty()) {
            // 优先放入本地缓存
            if (localMatCache.size() < 2) {
                localMatCache.offer(mat);
            }
            // 其次放入全局池
            else if (MAT_POOL.size() < MAX_MAT_POOL_SIZE) {
                MAT_POOL.offer(mat);
            }
            // 否则释放
            else {
                try {
                    mat.release();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 内存检查
     */
    private void checkMemoryPeriodically() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - LAST_GLOBAL_MEMORY_CHECK < MEMORY_CHECK_INTERVAL) {
            return;
        }
        LAST_GLOBAL_MEMORY_CHECK = currentTime;

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double usage = (double) usedMemory / maxMemory;

        if (usage > MEMORY_EMERGENCY_THRESHOLD) {
            triggerEmergencyCleanup();
        } else if (usage > MEMORY_CRITICAL_THRESHOLD) {
            triggerCriticalCleanup();
        } else if (usage > MEMORY_WARNING_THRESHOLD) {
            GLOBAL_MEMORY_PRESSURE.set(true);
            performPreventiveCleanup();
        } else {
            GLOBAL_MEMORY_PRESSURE.set(false);
        }
    }

    /**
     * 触发紧急清理
     */
    private void triggerEmergencyCleanup() {
        if (GLOBAL_EMERGENCY_MODE.compareAndSet(false, true)) {
            log.error("[触发全局紧急清理模式]");

            // 清理所有对象池
            clearAllObjectPools();

            // 强制GC
            forceGC();

            // 延迟恢复 - 使用传统方式
            new Thread(() -> {
                try {
                    Thread.sleep(10000); // 10秒后恢复
                    GLOBAL_EMERGENCY_MODE.set(false);
                    log.info("[退出紧急清理模式]");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    GLOBAL_EMERGENCY_MODE.set(false);
                }
            }, "EmergencyRecovery").start();
        }
    }

    /**
     * 关键清理
     */
    private void triggerCriticalCleanup() {
        log.warn("[执行关键内存清理]");
        clearPartialObjectPools();

        // 增加所有流的帧间隔
        adjustAllStreamsFrameInterval(true);
    }

    /**
     * 预防性清理
     */
    private void performPreventiveCleanup() {
        // 清理部分资源
        clearSmallObjectPools();

        // 适度调整帧率
        adjustAllStreamsFrameInterval(false);
    }

    /**
     * 清理所有对象池
     */
    private static void clearAllObjectPools() {
        // 清理Mat池
        while (MAT_POOL.size() > 0) {
            Mat mat = MAT_POOL.poll();
            if (mat != null) {
                try { mat.release(); } catch (Exception ignored) {}
            }
        }

        // 清理转换器池
        while (CONVERTER_POOL.size() > 0) {
            Java2DFrameConverter converter = CONVERTER_POOL.poll();
            if (converter != null) {
                try { converter.close(); } catch (Exception ignored) {}
            }
        }

        // 清理图像池
        IMAGE_POOL.clear();

        log.warn("[清理所有对象池完成]");
    }

    /**
     * 部分清理对象池
     */
    private static void clearPartialObjectPools() {
        int matCleared = 0;
        while (MAT_POOL.size() > MAX_MAT_POOL_SIZE / 2 && matCleared < 10) {
            Mat mat = MAT_POOL.poll();
            if (mat != null) {
                try { mat.release(); } catch (Exception ignored) {}
                matCleared++;
            }
        }

        int converterCleared = 0;
        while (CONVERTER_POOL.size() > MAX_CONVERTER_POOL_SIZE / 2 && converterCleared < 5) {
            Java2DFrameConverter converter = CONVERTER_POOL.poll();
            if (converter != null) {
                try { converter.close(); } catch (Exception ignored) {}
                converterCleared++;
            }
        }

        log.debug("[部分清理对象池] Mat: {}, 转换器: {}", matCleared, converterCleared);
    }

    /**
     * 小幅清理对象池
     */
    private static void clearSmallObjectPools() {
        // 只清理少量资源
        for (int i = 0; i < 3; i++) {
            Mat mat = MAT_POOL.poll();
            if (mat != null) {
                try { mat.release(); } catch (Exception ignored) {}
            }
        }

        Java2DFrameConverter converter = CONVERTER_POOL.poll();
        if (converter != null) {
            try { converter.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 强制GC
     */
    private static void forceGC() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - LAST_GLOBAL_GC > 5000) {
            LAST_GLOBAL_GC = currentTime;
            System.gc();
            System.runFinalization();

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 根据负载调整帧间隔
     */
    private void adjustFrameIntervalByLoad(int activeStreams) {
        if (activeStreams <= 8) {
            currentFrameInterval = MIN_FRAME_INTERVAL;        // 5秒
        } else if (activeStreams <= 16) {
            currentFrameInterval = MIN_FRAME_INTERVAL + 2000; // 7秒
        } else if (activeStreams <= 24) {
            currentFrameInterval = MIN_FRAME_INTERVAL + 5000; // 10秒
        } else {
            currentFrameInterval = MIN_FRAME_INTERVAL + 10000; // 15秒
        }
    }

    /**
     * 向上调整帧间隔
     */
    private void adjustFrameIntervalUp() {
        currentFrameInterval = Math.min(currentFrameInterval + 1000, 20000);
    }

    /**
     * 调整所有流的帧间隔
     */
    private void adjustAllStreamsFrameInterval(boolean critical) {
        // 这里通过日志通知调整建议，实际实现中可以使用事件机制
        if (critical) {
            log.warn("[建议所有流增加帧间隔] 推荐最小间隔: {}ms", MIN_FRAME_INTERVAL + 5000);
        } else {
            log.info("[建议适度调整帧间隔] 推荐最小间隔: {}ms", MIN_FRAME_INTERVAL + 2000);
        }
    }

    /**
     * 启动全局内存监控
     */
    private static void startGlobalMemoryMonitor() {
        ScheduledExecutorService monitor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "MemoryMonitor");
            t.setDaemon(true);
            return t;
        });

        monitor.scheduleAtFixedRate(() -> {
            try {
                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory();
                long usedMemory = runtime.totalMemory() - runtime.freeMemory();
                double usage = (double) usedMemory / maxMemory;

                if (usage > 0.8) {
                    log.warn("[全局内存监控] 使用率: {}%, 活跃流: {}, 处理中帧数: {}",
                            String.format("%.1f", usage * 100),
                            ACTIVE_STREAMS.get(),
                            CURRENT_PROCESSING_FRAMES.get());
                }

                // 池状态监控
                if (usage > 0.7) {
                    log.info("[资源池状态] Mat池: {}/{}, 转换器池: {}/{}, 图像池: {}/{}",
                            MAT_POOL.size(), MAX_MAT_POOL_SIZE,
                            CONVERTER_POOL.size(), MAX_CONVERTER_POOL_SIZE,
                            IMAGE_POOL.size(), MAX_IMAGE_POOL_SIZE);
                }

            } catch (Exception e) {
                log.error("[内存监控异常]", e);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    // ================== 原有方法的简化实现 ==================

    private void processNetPush(Mat mat, NetPush netPush, identifyTypeNew identifyType) {
        try {
            if (stopped.get() || GLOBAL_EMERGENCY_MODE.get()) {
                return;
            }

            if (netPush.getIsBefor() == 1) {
                // 处理有前置模型的情况
                processWithPredecessorsOptimized(mat, netPush, identifyType);
            } else {
                // 直接处理，无前置模型
                executeDetectionOptimized(mat, netPush, identifyType, null);
            }

        } catch (Exception e) {
            log.error("[NetPush处理异常] 流: {}, 模型: {}",
                    tabAiSubscriptionNew.getName(),
                    netPush.getTabAiModel() != null ? netPush.getTabAiModel().getAiName() : "unknown", e);
        }
    }

    /**
     * 处理前置模型 - 完整逻辑
     */
    private void processWithPredecessorsOptimized(Mat mat, NetPush netPush, identifyTypeNew identifyType) {
        List<NetPush> before = netPush.getListNetPush();
        if (before == null || before.isEmpty()) {
            return;
        }

        retureBoxInfo validationPassed = null;
        for (int i = 0; i < before.size(); i++) {
            if (stopped.get() || GLOBAL_EMERGENCY_MODE.get()) {
                break;
            }

            NetPush beforePush = before.get(i);

            try {
                if (i == 0) {
                    // 第一个模型验证
                    validationPassed = validateFirstModelOptimized(mat, beforePush, identifyType);
                    if (validationPassed == null || !validationPassed.isFlag()) {
                        log.warn("[第一个模型验证失败，终止后续处理] 流: {}", tabAiSubscriptionNew.getName());
                        break;
                    }
                } else {
                    // 后续模型使用第一个模型的结果
                    executeDetectionOptimized(mat, beforePush, identifyType,
                            validationPassed != null ? validationPassed.getInfoList() : null);
                }
            } catch (Exception e) {
                log.error("[前置模型处理异常] 流: {}, 模型索引: {}",
                        tabAiSubscriptionNew.getName(), i, e);
                break;
            }
        }
    }

    /**
     * 验证第一个模型
     */
    private retureBoxInfo validateFirstModelOptimized(Mat mat, NetPush beforePush, identifyTypeNew identifyType) {
        try {
            if (stopped.get() || GLOBAL_EMERGENCY_MODE.get()) {
                return null;
            }

            if ("1".equals(beforePush.getModelType())) {
                return identifyType.detectObjects(
                        tabAiSubscriptionNew, mat, beforePush.getNet(),
                        beforePush.getClaseeNames(), beforePush);
            } else {
                return identifyType.detectObjectsV5(
                        tabAiSubscriptionNew, mat, beforePush.getNet(),
                        beforePush.getClaseeNames(), beforePush);
            }
        } catch (Exception e) {
            log.error("[验证模型异常] 流: {}", tabAiSubscriptionNew.getName(), e);
            return null;
        }
    }

    /**
     * 执行检测 - 支持前置模型结果
     */
    private void executeDetectionOptimized(Mat mat, NetPush netPush, identifyTypeNew identifyType,
                                           List<retureBoxInfo> retureBoxInfos) {
        try {
            if (stopped.get() || GLOBAL_EMERGENCY_MODE.get()) {
                return;
            }

            long inferenceStart = System.currentTimeMillis();

            if ("1".equals(netPush.getModelType())) {
                identifyType.detectObjectsDify(tabAiSubscriptionNew, mat, netPush, redisTemplate, retureBoxInfos);
            } else {
                identifyType.detectObjectsDifyV5(tabAiSubscriptionNew, mat, netPush, redisTemplate, retureBoxInfos);
            }

            long inferenceTime = System.currentTimeMillis() - inferenceStart;
            if (inferenceTime > 1000) {
                log.warn("[推理耗时过长] 流: {}, 模型: {}, 耗时: {}ms",
                        tabAiSubscriptionNew.getName(),
                        netPush.getTabAiModel() != null ? netPush.getTabAiModel().getAiName() : "unknown",
                        inferenceTime);
            }

        } catch (Exception e) {
            log.error("[执行检测异常] 流: {}, 模型类型: {}",
                    tabAiSubscriptionNew.getName(), netPush.getModelType(), e);
        }
    }

    private void processBeforeModels(Mat mat, List<NetPush> beforeList, identifyTypeNew identifyType) {
        // 这个方法现在被 processWithPredecessorsOptimized 替代，保留以防兼容性需要
        processWithPredecessorsOptimized(mat, new NetPush() {{
            setListNetPush(beforeList);
            setIsBefor(1);
        }}, identifyType);
    }

    private boolean isStreamActive() {
        try {
            return RedisCacheHolder.get(tabAiSubscriptionNew.getId() + "newRunPush");
        } catch (Exception e) {
            log.warn("[检查流状态异常] 流: {}", tabAiSubscriptionNew.getName());
            return false;
        }
    }

    private FFmpegFrameGrabber restartGrabber(FFmpegFrameGrabber grabber) {
        if (grabber != null) {
            try { grabber.stop(); } catch (Exception ignored) {}
            try { grabber.release(); } catch (Exception ignored) {}
        }

        try {
            return createMemoryOptimizedGrabber();
        } catch (Exception e) {
            log.error("[重启采集器失败] 流: {}", tabAiSubscriptionNew.getName(), e);
            return null;
        }
    }

    private boolean checkGlobalMemoryState() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double usage = (double) usedMemory / maxMemory;
        if( usage < 0.9){
            log.info("当前JVM最大内存{},用户内存使用内存{},使用率{}",maxMemory,usedMemory,usage);
            return true;
        }else{
            return false;
        }
   //     return usage < 0.9; // 90%以上拒绝启动新流
    }

    private void performEmergencyCleanup() {
        log.error("[执行紧急清理] 流: {}", tabAiSubscriptionNew.getName());
        stopped.set(true);

        // 清理本地资源
        localMatCache.clear();

        // 触发全局清理
        triggerEmergencyCleanup();
    }

    private void logStats() {
        long processed = processedFrames.get();
        long dropped = droppedFrames.get();
        int pending = pendingFrames.get();

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsage = (double) usedMemory / runtime.maxMemory();

        log.info("[性能统计] 流: {}, 已处理: {}, 丢弃: {}, 排队: {}, 内存使用: {}%, 帧间隔: {}ms",
                tabAiSubscriptionNew.getName(), processed, dropped, pending,
                String.format("%.1f", memoryUsage * 100), currentFrameInterval);
    }

    private void cleanup(FFmpegFrameGrabber grabber) {
        log.info("[开始清理资源] 流: {}", tabAiSubscriptionNew.getName());

        stopped.set(true);

        // 清理采集器
        if (grabber != null) {
            try { grabber.stop(); } catch (Exception ignored) {}
            try { grabber.release(); } catch (Exception ignored) {}
        }

        // 清理本地资源
        Mat mat;
        while ((mat = localMatCache.poll()) != null) {
            try { mat.release(); } catch (Exception ignored) {}
        }

        // 清理ThreadLocal
        try {
            identifyTypeLocal.remove();
        } catch (Exception e) {
            log.warn("[ThreadLocal清理异常] 流: {}", tabAiSubscriptionNew.getName(), e);
        }

        // 减少活跃流计数
        int remaining = ACTIVE_STREAMS.decrementAndGet();
        log.info("[清理完成] 流: {}, 剩余活跃流: {}", tabAiSubscriptionNew.getName(), remaining);
    }

    public void forceStop() {
        log.info("[强制停止] 流: {}", tabAiSubscriptionNew.getName());
        stopped.set(true);
    }

    // ================== 静态工具方法 ==================

    /**
     * 关闭所有全局资源
     */
    public static void shutdownGlobalResources() {
        log.warn("[开始关闭全局资源]");

        // 设置紧急模式，停止所有处理
        GLOBAL_EMERGENCY_MODE.set(true);

        // 清理所有对象池
        clearAllObjectPools();

        // 关闭全局线程池
        if (GLOBAL_EXECUTOR != null && !GLOBAL_EXECUTOR.isShutdown()) {
            GLOBAL_EXECUTOR.shutdown();
            try {
                if (!GLOBAL_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    List<Runnable> pending = GLOBAL_EXECUTOR.shutdownNow();
                    log.warn("[强制关闭线程池] 剩余任务: {}", pending.size());
                }
            } catch (InterruptedException e) {
                GLOBAL_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 重置计数器
        ACTIVE_STREAMS.set(0);
        CURRENT_PROCESSING_FRAMES.set(0);

        log.warn("[全局资源关闭完成]");
    }

    /**
     * 获取全局统计信息
     */
    public static Map<String, Object> getGlobalStats() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("activeStreams", ACTIVE_STREAMS.get());
        stats.put("totalProcessedFrames", TOTAL_PROCESSED_FRAMES.get());
        stats.put("totalDroppedFrames", TOTAL_DROPPED_FRAMES.get());
        stats.put("currentProcessingFrames", CURRENT_PROCESSING_FRAMES.get());

        stats.put("matPoolSize", MAT_POOL.size());
        stats.put("converterPoolSize", CONVERTER_POOL.size());
        stats.put("imagePoolSize", IMAGE_POOL.size());

        stats.put("globalMemoryPressure", GLOBAL_MEMORY_PRESSURE.get());
        stats.put("globalEmergencyMode", GLOBAL_EMERGENCY_MODE.get());

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        stats.put("memoryUsed", usedMemory);
        stats.put("memoryTotal", runtime.totalMemory());
        stats.put("memoryMax", runtime.maxMemory());
        stats.put("memoryUsagePercent", (double) usedMemory / runtime.maxMemory() * 100);

        if (GLOBAL_EXECUTOR instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) GLOBAL_EXECUTOR;
            stats.put("threadPoolActive", tpe.getActiveCount());
            stats.put("threadPoolQueueSize", tpe.getQueue().size());
            stats.put("threadPoolCompleted", tpe.getCompletedTaskCount());
        }

        return stats;
    }

    /**
     * 手动触发内存清理
     */
    public static void triggerManualCleanup() {
        log.info("[手动触发内存清理]");
        clearPartialObjectPools();
        forceGC();
    }
}