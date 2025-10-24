package org.jeecg.modules.demo.video.util.batch.batch;


import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.util.batch.batch.BatchInferenceScheduler;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 批量推理分类测试
 */
@Slf4j
@Component
public class BatchInferenceTest {

    @Autowired
    private BatchInferenceScheduler scheduler;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 测试类型1: 批量后置检测(无前置)
     */
    public void testType1_BatchPostOnly() throws Exception {
        log.info("========== 测试类型1: 批量后置检测 ==========");

        // 1. 准备测试数据
        Mat frame = Imgcodecs.imread("test.jpg");

        TabAiSubscriptionNew pushInfo = createTestPushInfo();

        // 2. 创建后置模型配置(无前置)
        NetPush postModel = createPostModel();
        postModel.setIsBefor(1);  // ✅ 关键:标记无前置
        postModel.setDifyType(0); // ✅ 普通检测

        List<NetPush> netPushList = new ArrayList<>();
        netPushList.add(postModel);

        // 3. 提交任务
        CompletableFuture<Boolean> future = scheduler.submitFullInference(
                "test-stream-1",
                frame,
                netPushList,
                pushInfo
        );

        // 4. 等待结果
        Boolean result = future.get(10, TimeUnit.SECONDS);
        log.info("[类型1测试结果] 成功: {}", result);

        // 5. 验证日志应该显示: [分类:直接推理] -> [提交普通后置]
    }

    /**
     * 测试类型2: 批量前置+后置+ROI
     */
    public void testType2_BatchPreAndPost() throws Exception {
        log.info("========== 测试类型2: 批量前置+后置+ROI ==========");

        // 1. 准备测试数据
        Mat frame = Imgcodecs.imread("test.jpg");

        TabAiSubscriptionNew pushInfo = createTestPushInfo();

        // 2. 创建前置+后置模型配置
        NetPush preModel = createPreModel();  // 前置:检测人
        NetPush postModel1 = createPostModel(); // 后置1:检测安全帽
        NetPush postModel2 = createPostModel(); // 后置2:检测反光衣

        // 构建前置+后置结构
        List<NetPush> beforeList = new ArrayList<>();
        beforeList.add(preModel);   // 索引0:前置
        beforeList.add(postModel1); // 索引1:后置1
        beforeList.add(postModel2); // 索引2:后置2

        NetPush mainModel = new NetPush();
        mainModel.setIsBefor(0);  // ✅ 关键:标记有前置
        mainModel.setListNetPush(beforeList);

        List<NetPush> netPushList = new ArrayList<>();
        netPushList.add(mainModel);

        // 3. 提交任务
        CompletableFuture<Boolean> future = scheduler.submitFullInference(
                "test-stream-2",
                frame,
                netPushList,
                pushInfo
        );

        // 4. 等待结果
        Boolean result = future.get(10, TimeUnit.SECONDS);
        log.info("[类型2测试结果] 成功: {}", result);

        // 5. 验证日志应该显示:
        //    [分类:前置+后置] -> [提交前置模型] -> [前置模型检测成功] -> [提交普通后置] * 2
    }

    /**
     * 测试类型3: 批量姿态识别
     */
    public void testType3_BatchPose() throws Exception {
        log.info("========== 测试类型3: 批量姿态识别 ==========");

        // 1. 准备测试数据
        Mat frame = Imgcodecs.imread("test.jpg");

        TabAiSubscriptionNew pushInfo = createTestPushInfo();

        // 2. 创建姿态识别模型配置
        NetPush poseModel = createPoseModel();
        poseModel.setIsBefor(1);  // ✅ 无前置
        poseModel.setDifyType(2); // ✅ 关键:标记为姿态识别

        List<NetPush> netPushList = new ArrayList<>();
        netPushList.add(poseModel);

        // 3. 提交任务
        CompletableFuture<Boolean> future = scheduler.submitFullInference(
                "test-stream-3",
                frame,
                netPushList,
                pushInfo
        );

        // 4. 等待结果
        Boolean result = future.get(10, TimeUnit.SECONDS);
        log.info("[类型3测试结果] 成功: {}", result);

        // 5. 验证日志应该显示: [分类:直接推理] -> [提交姿态识别]
    }

    /**
     * 测试类型2变体: 前置+姿态后置
     */
    public void testType2Variant_PreAndPose() throws Exception {
        log.info("========== 测试类型2变体: 前置+姿态后置 ==========");

        // 1. 准备测试数据
        Mat frame = Imgcodecs.imread("test.jpg");

        TabAiSubscriptionNew pushInfo = createTestPushInfo();

        // 2. 创建前置+姿态后置配置
        NetPush preModel = createPreModel();  // 前置:检测人
        NetPush poseModel = createPoseModel(); // 后置:姿态分析
        poseModel.setDifyType(2); // ✅ 关键:姿态识别

        List<NetPush> beforeList = new ArrayList<>();
        beforeList.add(preModel);  // 索引0:前置
        beforeList.add(poseModel); // 索引1:姿态后置

        NetPush mainModel = new NetPush();
        mainModel.setIsBefor(0);  // ✅ 有前置
        mainModel.setListNetPush(beforeList);

        List<NetPush> netPushList = new ArrayList<>();
        netPushList.add(mainModel);

        // 3. 提交任务
        CompletableFuture<Boolean> future = scheduler.submitFullInference(
                "test-stream-4",
                frame,
                netPushList,
                pushInfo
        );

        // 4. 等待结果
        Boolean result = future.get(10, TimeUnit.SECONDS);
        log.info("[类型2变体测试结果] 成功: {}", result);

        // 5. 验证日志应该显示:
        //    [分类:前置+后置] -> [提交前置模型] -> [提交姿态后置]
    }

    /**
     * 综合测试:32路视频同时推理
     */
    public void testBatchConcurrent() throws Exception {
        log.info("========== 综合测试:32路并发 ==========");

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < 32; i++) {
            final int streamIndex = i;

            Mat frame = Imgcodecs.imread("test" + i + ".jpg");
            TabAiSubscriptionNew pushInfo = createTestPushInfo();

            // 随机分配不同类型的模型
            List<NetPush> netPushList;
            if (i % 3 == 0) {
                // 类型1:普通后置
                netPushList = createType1Config();
            } else if (i % 3 == 1) {
                // 类型2:前置+后置
                netPushList = createType2Config();
            } else {
                // 类型3:姿态识别
                netPushList = createType3Config();
            }

            CompletableFuture<Boolean> future = scheduler.submitFullInference(
                    "stream-" + streamIndex,
                    frame,
                    netPushList,
                    pushInfo
            );

            futures.add(future);
        }

        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

        long successCount = futures.stream()
                .filter(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        return false;
                    }
                }).count();

        log.info("[并发测试完成] 总数:32, 成功:{}, 失败:{}",
                successCount, 32 - successCount);

        // 打印统计信息
        log.info("[调度器统计] {}", scheduler.getStatistics());
    }

    // ========== 辅助方法 ==========

    private TabAiSubscriptionNew createTestPushInfo() {
        TabAiSubscriptionNew pushInfo = new TabAiSubscriptionNew();
        pushInfo.setId("test-push-" + System.currentTimeMillis());
        pushInfo.setName("测试摄像头");
        pushInfo.setEventNumber("60"); // 60秒间隔
        pushInfo.setBeginEventTypes("rtsp://test.com/stream");
        return pushInfo;
    }

    private NetPush createPreModel() {
        NetPush netPush = new NetPush();
        netPush.setId("pre-model-" + System.currentTimeMillis());
        netPush.setBeforText("person"); // 前置目标:检测人
        // ... 设置ONNX session、classNames等
        return netPush;
    }

    private NetPush createPostModel() {
        NetPush netPush = new NetPush();
        netPush.setId("post-model-" + System.currentTimeMillis());
        netPush.setDifyType(0); // 普通检测
        // ... 设置ONNX session、classNames等
        return netPush;
    }

    private NetPush createPoseModel() {
        NetPush netPush = new NetPush();
        netPush.setId("pose-model-" + System.currentTimeMillis());
        netPush.setDifyType(2); // ✅ 姿态识别
        // ... 设置ONNX session、classNames等
        return netPush;
    }

    private List<NetPush> createType1Config() {
        List<NetPush> list = new ArrayList<>();
        NetPush model = createPostModel();
        model.setIsBefor(1); // 无前置
        list.add(model);
        return list;
    }

    private List<NetPush> createType2Config() {
        NetPush mainModel = new NetPush();
        mainModel.setIsBefor(0); // 有前置

        List<NetPush> beforeList = new ArrayList<>();
        beforeList.add(createPreModel());  // 前置
        beforeList.add(createPostModel()); // 后置
        mainModel.setListNetPush(beforeList);

        List<NetPush> list = new ArrayList<>();
        list.add(mainModel);
        return list;
    }

    private List<NetPush> createType3Config() {
        List<NetPush> list = new ArrayList<>();
        NetPush model = createPoseModel();
        model.setIsBefor(1); // 无前置
        model.setDifyType(2); // 姿态识别
        list.add(model);
        return list;
    }
}

/**
 * 预期日志输出示例:
 *
 * ========== 类型1:批量后置检测 ==========
 * [分类:直接推理] streamId:test-stream-1, 模型数:1
 * [提交普通后置] streamId:test-stream-1, 模型:helmet-detection
 * [后置批量推理开始] 批次大小:6, 模型:helmet-detection
 * [后置批量推理完成] 批次大小:6, 成功:5
 *
 * ========== 类型2:前置+后置 ==========
 * [分类:前置+后置] streamId:test-stream-2, 前置模型数:3
 * [提交前置模型] streamId:test-stream-2, 模型:person-detection
 * [前置批量推理开始] 批次大小:6, 模型:person-detection
 * [前置批量推理完成] 批次大小:6, 平均ROI数:2.5
 * [前置模型检测成功] streamId:test-stream-2, ROI数量:3
 * [提交普通后置] streamId:test-stream-2, 模型:helmet-detection
 * [提交普通后置] streamId:test-stream-2, 模型:vest-detection
 * [后置批量推理完成] 批次大小:6, 成功:4
 *
 * ========== 类型3:姿态识别 ==========
 * [分类:直接推理] streamId:test-stream-3, 模型数:1
 * [提交姿态识别] streamId:test-stream-3, 模型:pose-detection
 * [姿态批量推理开始] 批次大小:6
 * [姿态批量推理完成] 批次大小:6, 成功:5
 * ✅ 姿态检测: 状态=跌倒, 置信度=0.95, 原因=躯干角度过小:35.0°
 */
