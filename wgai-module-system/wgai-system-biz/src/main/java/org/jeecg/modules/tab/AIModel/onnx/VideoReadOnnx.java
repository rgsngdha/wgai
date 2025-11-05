package org.jeecg.modules.tab.AIModel.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.tab.entity.TabAiBase;
import org.jeecg.modules.demo.tab.entity.TabAiModelBund;
import org.jeecg.modules.demo.video.util.RedisCacheHolder;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.message.websocket.WebSocket;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.jeecg.modules.tab.AIModel.VideoSendReadCfg;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.utils.Converters;
import org.springframework.data.redis.core.RedisTemplate;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.jeecg.modules.demo.video.util.identifyTypeNewOnnx.letterboxResize;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.CommonColorsVue;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;

/**
 * @author wggg
 * @date 2025/10/31 15:16
 */
@Slf4j
public class VideoReadOnnx implements Runnable{

    private RedisTemplate redisTemplate;

    public Integer TARGET_FRAME_INTERVAL=1000; //视频间隔1s

    public String videoUrl;

    public String userId;
    private volatile long lastFrameTime = 0;

    public String namesUrl;
    public String cfgUrl;
    public String weightUrl;
    public WebSocket webSocket;
    public TabAiModelBund tabAiModelBund;

    public TabAudioDevice tabAudioDevice;
    NetPush netpush;
    String uuid;
    public VideoReadOnnx(NetPush netpush, TabAudioDevice tabAudioDevice, TabAiModelBund tabAiModelBund, String videoUrl, RedisTemplate redisTemplate, String userId, String namesUrl,  WebSocket webSocket){
        this.videoUrl=videoUrl;
        this.redisTemplate=redisTemplate;
        this.userId=userId;
        this.namesUrl=namesUrl;
        this.webSocket=webSocket;
        this.netpush=netpush;
        this.tabAiModelBund=tabAiModelBund;
        this.tabAudioDevice=tabAudioDevice;
    }

    @Override
    public void run() {
        FFmpegFrameGrabber grabber = null;
        Frame frame;
        int consecutiveNullFrames = 0;
        try {
             grabber= createOptimizedGrabber();
             while (true){
                 if (!isStreamActive()) {
                     log.warn("[主动停止推送]{}",uuid);
                     break;
                 }
                 frame = grabber.grabImage();
                 if (frame == null) {
                     consecutiveNullFrames++;
                     if (consecutiveNullFrames > 10) {
                         log.info("[连续空帧过多，重启视频流]");
                         grabber = restartGrabber(grabber);
                         consecutiveNullFrames = 0;
                     }
                     Thread.sleep(100); // 减少等待时间
                     continue;
                 }
                 consecutiveNullFrames = 0;

                 // 关键修改4：严格的实时帧率控制
                 long currentTime = System.currentTimeMillis();
                 if (currentTime - lastFrameTime < TARGET_FRAME_INTERVAL) {
                     frame.close(); // 立即释放不需要的帧
                     continue;
                 }
                 lastFrameTime = currentTime;
                 long timestamp = grabber.getTimestamp() / 1000;
                 processFrameAsyncOptimized(frame,timestamp);
             }

        } catch (Exception e) {
           e.printStackTrace();

        }

    }

    private void processFrameAsyncOptimized(Frame frame,    long timestamp ){
        Frame frameClone = null;
        try {
            frameClone = frame.clone();
        } catch (Exception e) {
            log.error("[Frame克隆失败]: {}", e.getMessage());

            return;
        }
        BufferedImage image = null;
        Mat matInfo = null;
        // 快速转换
        final Frame finalFrame = frameClone;
        Java2DFrameConverter converter =new Java2DFrameConverter();
        image = converter.getBufferedImage(finalFrame);
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return;
        }



        matInfo = bufferedImageToMat(image);
        if (matInfo == null || matInfo.empty()) {
            return;
        }

        detectObjectsDifyOnnxV5(  matInfo,  netpush,
                 redisTemplate, null,timestamp);

    }
    public boolean detectObjectsDifyOnnxV5( Mat image, NetPush netPush,
                                           RedisTemplate redisTemplate, List<retureBoxInfo> retureBoxInfos,long timestamp) {



        // ========== 2. 初始化参数 ==========
        List<String> classNames = netPush.getClaseeNames();
        Integer expectedClassCount = classNames.size();
        TabAiModel tabAiModel = netPush.getTabAiModel();


        long startTime = System.currentTimeMillis();

        // ========== 3. 图像预处理 ==========
        Mat processedImage = letterboxResize(image, 640, 640);
        //  Imgproc.cvtColor(processedImage, processedImage, Imgproc.COLOR_BGR2RGB);
        float[] inputData = preprocessImage(processedImage);

        // ========== 4. ONNX推理 ==========
        OrtSession session = netPush.getSession();
        OrtEnvironment env = netPush.getEnv();

        DetectionResult detectionResult;
        try {
            detectionResult = runOnnxInference(session, env, inputData, expectedClassCount);
        } catch (Exception ex) {
            log.error("ONNX推理失败", ex);
            return false;
        }

        // ========== 5. 检测结果验证 ==========
        int detectionCount = detectionResult.confidences.size();
        if (detectionCount <= 0 || detectionCount > 200) {
            log.warn("{}:检测数量异常: {}-{}",detectionCount);

            return false;
        }

        log.info("NMS前检测框数量: {}", detectionResult.boxes2d.size());

        // ========== 6. NMS非极大值抑制 ==========
        int[] nmsIndices = performNMS(detectionResult, 0.35f, 0.35f);
        if (nmsIndices.length > 50) {

            log.warn("NMS后检测框数量过多: {}, 超过阈值50", nmsIndices.length);
            return false;
        }
        log.info("NMS后检测框数量: {}", nmsIndices.length);

        // ========== 7. 过滤和绘制检测框 ==========

        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        DetectionStats stats = new DetectionStats();
        int validCount = 0;
        JSONObject bja=new JSONObject();
        List<JSONObject>  jsonlist=new ArrayList<>();
        for (int idx : nmsIndices) {
            Rect2d box = detectionResult.boxes2d.get(idx);
            Integer classId = detectionResult.classIds.get(idx);
            String className = classNames.get(classId);
            float confidence = detectionResult.confidences.get(idx);

            // 坐标还原到原图
            BoundingBox originalBox = restoreCoordinates(box, scale, dx, dy, image);


            // 获取类别配置
            TabAiBase aiBase = getAiBaseConfig(className);

            bja.put("cmd", "video");
            JSONObject bj=new JSONObject();
            bj.put("x", originalBox.x);
            bj.put("y", originalBox.y);
            bj.put("width", originalBox.width);
            bj.put("height",originalBox.height);
            bj.put("url", videoUrl);
            bj.put("name", aiBase.getChainName());
            bj.put("color", CommonColorsVue(classId));
            bj.put("number",timestamp);
            bja.put("number",timestamp);
            jsonlist.add(bj);
            bja.put("list",jsonlist);
//            if (aiBase == null || shouldSkipClass(aiBase)) {
//                log.warn("【跳过类别：{}】", className);
//                continue;
//            }
//
//            // 累计统计信息
//            stats.accumulate(aiBase);
//
//            // 绘制检测框
//            Scalar color = getColor(aiBase.getRgbColor());
//            image=drawDetection(image, originalBox, aiBase.getChainName(), confidence, color);

            validCount++;
        }





        long endTime = System.currentTimeMillis();
        log.info("识别耗时: {}ms, 有效检测: {}/{}", (endTime - startTime), validCount, nmsIndices.length);
        webSocket.sendMessage(bja.toJSONString());

        try {

            return true;
        } catch (Exception ex) {
            log.warn("推送失败", ex);
            return false;
        }
    }
    private TabAiBase getAiBaseConfig(String className) {
        TabAiBase aiBase = VideoSendReadCfg.map.get(className);
        if (aiBase == null) {
            aiBase = new TabAiBase();
            aiBase.setChainName(className);
        }
        return aiBase;
    }


    /**
     * 坐标还原
     */
    private BoundingBox restoreCoordinates(Rect2d box, double scale, double dx, double dy, Mat image) {
        double x = Math.max(0, Math.min((box.x - dx) / scale, image.cols() - 1));
        double y = Math.max(0, Math.min((box.y - dy) / scale, image.rows() - 1));
        double w = Math.min(box.width / scale, image.cols() - x);
        double h = Math.min(box.height / scale, image.rows() - y);

        return new BoundingBox(x, y, w, h);
    }

    /**
     * 边界框封装类
     */
    static class BoundingBox {
        double x, y, width, height;

        BoundingBox(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
    /**
     * 统计信息封装类
     */
    static class DetectionStats {
        String audioText = "";
        Integer warnNumber = 0;
        String warnText = "";
        String warnName = "";

        void accumulate(TabAiBase aiBase) {
            audioText += aiBase.getRemark() + aiBase.getSpaceOne();
            warnNumber += aiBase.getSpaceTwo() == null ? 1 : aiBase.getSpaceTwo();
//            warnText = setNmsName(warnText,
//                    StringUtils.isEmpty(aiBase.getRemark()) ?
//                            aiBase.getChainName() : aiBase.getRemark());
//            warnName = setNmsName(warnName, aiBase.getChainName());
        }
    }
    /**
     * NMS非极大值抑制
     */
    private int[] performNMS(DetectionResult detectionResult, float confThreshold, float nmsThreshold) {
        if (detectionResult.boxes2d.isEmpty()) {
            return new int[0];
        }

        MatOfRect2d boxesMat = new MatOfRect2d();
        boxesMat.fromList(detectionResult.boxes2d);

        MatOfFloat confMat = new MatOfFloat(Converters.vector_float_to_Mat(detectionResult.confidences));
        MatOfInt indices = new MatOfInt();

        Dnn.NMSBoxes(boxesMat, confMat, confThreshold, nmsThreshold, indices);
        // 检查NMS结果
        if (indices.empty() || indices.rows() == 0) {
            log.warn("NMS未返回任何索引，可能置信度阈值{}过高", confThreshold);
            return new int[0];
        }
        return indices.toArray();
    }
    /**
     * 解析ONNX输出（支持YOLOv5-v11）
     */
    private void parseOnnxOutput(Object rawOutput, long[] tensorShape, Integer expectedClassCount,
                                 float confThreshold, DetectionResult result) {
        if (rawOutput instanceof float[][][]) {
            float[][][] batch = (float[][][]) rawOutput;

            // 判断是否需要转置 (YOLOv11: [1, 84, 8400])
            boolean needTranspose = tensorShape.length == 3 &&
                    tensorShape[1] < tensorShape[2] &&
                    tensorShape[1] <= (expectedClassCount + 5);

            for (float[][] detections : batch) {
                if (needTranspose) {
                    parseTransposedDetections(detections, tensorShape, expectedClassCount, confThreshold, result);
                } else {
                    parseStandardDetections(detections, expectedClassCount, confThreshold, result);
                }
            }
        } else if (rawOutput instanceof float[][]) {
            parseStandardDetections((float[][]) rawOutput, expectedClassCount, confThreshold, result);
        }
    }
    /**
     * 解析转置格式（YOLOv11）
     */
    private void parseTransposedDetections(float[][] detections, long[] tensorShape,
                                           Integer expectedClassCount, float confThreshold,
                                           DetectionResult result) {
        int numFeatures = (int) tensorShape[1];
        int numDetections = (int) tensorShape[2];
        int numClasses = numFeatures - 4;

        for (int i = 0; i < numDetections; i++) {
            float cx = detections[0][i];
            float cy = detections[1][i];
            float w = detections[2][i];
            float h = detections[3][i];

            // 找最高分类别
            float maxScore = 0;
            int classId = 0;
            for (int c = 0; c < numClasses; c++) {
                if (detections[4 + c][i] > maxScore) {
                    maxScore = detections[4 + c][i];
                    classId = c;
                }
            }

            if (maxScore > confThreshold && classId < expectedClassCount) {
                result.addDetection(cx - w / 2, cy - h / 2, w, h, maxScore, classId);
            }
        }
    }

    /**
     * 解析标准格式（YOLOv5/v8）
     */
    private void parseStandardDetections(float[][] detections, Integer expectedClassCount,
                                         float confThreshold, DetectionResult result) {
        for (float[] det : detections) {
            boolean hasObjectness = det.length > 5;
            int startIdx = hasObjectness ? 5 : 4;

            // 找最高分类别
            float maxScore = 0;
            int classId = 0;
            for (int i = startIdx; i < det.length; i++) {
                if (det[i] > maxScore) {
                    maxScore = det[i];
                    classId = i - startIdx;
                }
            }

            float confidence = hasObjectness ? det[4] * maxScore : maxScore;

            if (confidence > confThreshold && classId < expectedClassCount) {
                float cx = det[0], cy = det[1], w = det[2], h = det[3];
                result.addDetection(cx - w / 2, cy - h / 2, w, h, confidence, classId);
            }
        }
    }
    /**
     * ONNX推理
     */
    private DetectionResult runOnnxInference(OrtSession session, OrtEnvironment env,
                                                                 float[] inputData, Integer expectedClassCount) throws Exception {
        long[] shape = new long[]{1, 3, 640, 640};

        DetectionResult result = new DetectionResult();
        float confThreshold = 0.45f;

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape)) {
            Map<String, OnnxTensor> inputs = Collections.singletonMap(
                    session.getInputNames().iterator().next(), inputTensor);

            try (OrtSession.Result results = session.run(inputs)) {
                for (Map.Entry<String, OnnxValue> entry : results) {
                    if (!(entry.getValue() instanceof OnnxTensor)) continue;

                    OnnxTensor tensor = (OnnxTensor) entry.getValue();
                    long[] tensorShape = tensor.getInfo().getShape();
                    Object rawOutput = tensor.getValue();

                    parseOnnxOutput(rawOutput, tensorShape, expectedClassCount, confThreshold, result);
                }
            }
        }

        return result;
    }
    /**
     * 检测结果
     */
    private static class DetectionResult {
        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();

        void addDetection(double x, double y, double w, double h, float confidence, int classId) {
            boxes2d.add(new Rect2d(x, y, w, h));
            confidences.add(confidence);
            classIds.add(classId);
        }
    }

    private float[] preprocessImage(Mat processedImage) {
        Mat blob = new Mat();
        processedImage.convertTo(blob, CvType.CV_32F, 1.0 / 255.0);

        List<Mat> channels = new ArrayList<>();
        Core.split(blob, channels);

        float[] inputData = new float[3 * 640 * 640];
        for (int c = 0; c < 3; c++) {
            float[] data = new float[640 * 640];
            channels.get(c).get(0, 0, data);
            System.arraycopy(data, 0, inputData, c * 640 * 640, 640 * 640);
        }
        return inputData;
    }
    private FFmpegFrameGrabber restartGrabber(FFmpegFrameGrabber grabber) throws Exception {
        if (grabber != null) {
            grabber.stop();
            grabber.release();
        }
        return createOptimizedGrabber();
    }

    private boolean isStreamActive() {
        try {
            return RedisCacheHolder.get(tabAiModelBund.getId() + "videoRead");
        } catch (Exception e) {
            log.warn("[检查流状态异常]", e);
            return false;
        }
    }
    public FFmpegFrameGrabber createOptimizedGrabber() throws Exception {

        // 第一步：先探测流信息
        FFmpegFrameGrabber probe = new FFmpegFrameGrabber(videoUrl);
        probe.setOption("rtsp_transport", "tcp");
        probe.setOption("stimeout", "5000000");
        probe.start();
        String codecName = probe.getVideoCodecName();
        int codecId = probe.getVideoCodec();
        probe.stop();
        probe.close();
        probe.release();
        log.info(" 检测到视频编码: " + codecName + " (ID=" + codecId + ")");
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoUrl);

        // GPU设置
//        if (tabAiSubscriptionNew.getEventTypes().equals("1")) {
//
//
//            grabber.setOption("hwaccel", "cuda");
//            grabber.setOption("hwaccel_device", "0");
//            grabber.setOption("hwaccel_output_format", "cuda");
//            log.info("[使用GPU_CUDA加速解码]");
//        }else { //if(tabAiSubscriptionNew.getEventTypes().equals("4"))
            //intel 加速
            grabber.setOption("hwaccel", "qsv");          // Intel QuickSync
            //grabber.setVideoCodecName("hevc_qsv");         // H.265 QSV
            if ("h264".equalsIgnoreCase(codecName)) {
                grabber.setVideoCodecName("h264_qsv");
            } else if ("hevc".equalsIgnoreCase(codecName) || "hevc1".equalsIgnoreCase(codecName)) {
                grabber.setVideoCodecName("hevc_qsv");
            }
            log.info("[使用Intel加速解码]");
    //    }
        // 基础设置
        grabber.setOption("loglevel", "-8");
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");
        grabber.setOption("stimeout", "3000000");

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

}
