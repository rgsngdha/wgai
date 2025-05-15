package org.jeecg.modules.tab.AIModel;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.common.util.RestUtil;
import org.jeecg.modules.demo.tab.entity.PushInfo;
import org.jeecg.modules.demo.tab.entity.TabAiBase;
import org.jeecg.modules.tab.AIModel.identify.identifyTypeAll;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.entity.pushEntity;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.utils.Converters;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.jeecg.modules.demo.audio.util.audioSend.getToken;
import static org.jeecg.modules.demo.audio.util.audioSend.postAudioText;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.*;

/**
 * @author Administrator
 * @date 2024/4/9 14:33
 */
@Slf4j
public class VideoReadPic implements Runnable{
    private static final ThreadLocal<PushInfo> threadLocalPushInfo = new ThreadLocal<>();
     String uploadpath="D:\\opt\\upFiles";
     PushInfo pushInfo;
     Long LastTime= 0L;
    RedisTemplate redisTemplate;
    public VideoReadPic(PushInfo pushInfo,String uploadpath,RedisTemplate redisTemplate){
        this.pushInfo=pushInfo;
        this.redisTemplate=redisTemplate;
        this.uploadpath=uploadpath;
    }

    @Override
    public void run() {
        threadLocalPushInfo.set(pushInfo);
        pushInfo=threadLocalPushInfo.get();
        log.info("开始识别！"+pushInfo.getPushUrl()+"前置条件{}"+pushInfo.getIsBegin());
        List<TabAiModel> tabAiModels=pushInfo.getTabAiModelList();
        List<NetPush> nets = new ArrayList<>();
        List<String> claseeNames=new ArrayList<>();

        if(pushInfo.getIsBegin()==0){//有前置条件

            TabAiModel tabAiModel=pushInfo.getBeginEventTypes();
            log.info("进入前置条件"+pushInfo.getPyType()+"当前类型"+tabAiModel.getSpareOne());
            NetPush netmap =new NetPush();
            Net net = null;
            if(tabAiModel.getSpareOne().equals("1")){  //v3
                net=Dnn.readNetFromDarknet(uploadpath+ File.separator +tabAiModel.getAiConfig(), uploadpath+ File.separator +tabAiModel.getAiWeights());
            } else if (tabAiModel.getSpareOne().equals("2")||tabAiModel.getSpareOne().equals("3")) { //v5 v8
                net = Dnn.readNetFromONNX(uploadpath+ File.separator +tabAiModel.getAiWeights());
            }
            net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            net.setPreferableTarget(Dnn.DNN_TARGET_CPU);  //cpu推理
            claseeNames.add(uploadpath+ File.separator +tabAiModel.getAiNameName());
            netmap.setNet(net);
            netmap.setModelType(tabAiModel.getSpareOne());
            nets.add(netmap);
        }


        for (TabAiModel tabAiModel:tabAiModels) {
            NetPush netmap =new NetPush();
            Net net = null;
            if(tabAiModel.getSpareOne().equals("1")){  //v3
                net=Dnn.readNetFromDarknet(uploadpath+ File.separator +tabAiModel.getAiConfig(), uploadpath+ File.separator +tabAiModel.getAiWeights());
            } else if (tabAiModel.getSpareOne().equals("2")||tabAiModel.getSpareOne().equals("3")) { //v5 v8
                net = Dnn.readNetFromONNX(uploadpath+ File.separator +tabAiModel.getAiWeights());
            }
            net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            net.setPreferableTarget(Dnn.DNN_TARGET_CPU);  //cpu推理
            claseeNames.add(uploadpath+ File.separator +tabAiModel.getAiNameName());
            netmap.setNet(net);
            netmap.setModelType(tabAiModel.getSpareOne());
            nets.add(netmap);

        }


        Mat frame = new Mat();
        String videoUrl=pushInfo.getVideoURL();

        if(pushInfo.getPyType()==5){ // pytype字典
            log.info("解码类型= 5 =开始解析：{}",videoUrl);
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoUrl);
            try {
//                grabber.setVideoOption("hwaccel", "cuda"); // NVIDIA CUDA 硬解码
////                grabber.setVideoOption("hwaccel_device", "0"); // GPU 设备号
                grabber.setOption("threads", "auto"); // 自动选择线程数
                grabber.setOption("preset", "ultrafast"); // 快速解码
                grabber.setVideoOption("tune", "zerolatency"); // 低延迟模式
                grabber.setOption("rtsp_transport", "tcp");  // 强制使用 TCP
                grabber.setOption("max_delay", "500000");    // 设置最大延迟
                grabber.setOption("buffer_size", "10485760"); // 设置10MB的缓冲区
                grabber.setOption("fflags", "nobuffer"); // 不缓存旧帧，尽量读取最新帧
                grabber.setOption("flags", "low_delay"); // 降低延迟
                grabber.setOption("framedrop", "1"); // 在解码压力大时丢帧
                grabber.setOption("analyzeduration", "0"); // 减少分析时间
                grabber.setOption("probesize", "32"); // 降低探测数据大小，快速锁定流
                grabber.setOption("stimeout", "3000000");    // 3秒超时
                grabber.setOption("skip_frame", "nokey"); // 只解码关键帧（I帧）
                grabber.setOption("hwaccel", "auto"); // 硬件加速
                grabber.setOption("pixel_format", "yuv420p"); // 像素格式
                grabber.setOption("an", "1"); // 禁用音频
                grabber.setOption("skip_frame", "nokey");// 跳过损坏帧
                grabber.setOption("strict", "experimental");// 设置更严格的解码
                grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
                grabber.start(); // 开始读取视频

                log.info("读取成功视频：{}",videoUrl);
            } catch (Exception e) {
               e.printStackTrace();
            }
            Java2DFrameConverter converter = new Java2DFrameConverter();
            try {


                while (true) {

                    // 将Frame转换为OpenCV的Mat对象
                    boolean flag= (boolean) redisTemplate.opsForValue().get(pushInfo.getPushId()+"isRunPush");
                    if(!flag){
                        log.info("结束推送");
                        break;
                    }
                    try {

                        Frame frames = grabber.grab();
                        if(frames==null){
                            grabber.stop();
                            grabber.release();
                            grabber.setOption("threads", "auto"); // 自动选择线程数
                            grabber.setOption("preset", "ultrafast"); // 快速解码
                            grabber.setVideoOption("tune", "zerolatency"); // 低延迟模式
                            grabber.setOption("rtsp_transport", "tcp");  // 强制使用 TCP
                            grabber.setOption("max_delay", "500000");    // 设置最大延迟
                            grabber.setOption("buffer_size", "10485760"); // 设置10MB的缓冲区
                            grabber.setOption("fflags", "nobuffer"); // 不缓存旧帧，尽量读取最新帧
                            grabber.setOption("flags", "low_delay"); // 降低延迟
                            grabber.setOption("framedrop", "1"); // 在解码压力大时丢帧
                            grabber.setOption("analyzeduration", "0"); // 减少分析时间
                            grabber.setOption("probesize", "32"); // 降低探测数据大小，快速锁定流
                            grabber.setOption("stimeout", "3000000");    // 3秒超时
                            grabber.setOption("skip_frame", "nokey"); // 只解码关键帧（I帧）
                            grabber.setOption("hwaccel", "auto"); // 硬件加速
                            grabber.setOption("pixel_format", "yuv420p"); // 像素格式
                            grabber.setOption("an", "1"); // 禁用音频
                            grabber.setOption("skip_frame", "nokey");// 跳过损坏帧
                            grabber.setOption("strict", "experimental");// 设置更严格的解码
                            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
                            grabber.start(); // 开始读取视频
                            // 获取实际的像素格式
                            int pixelFormat = grabber.getPixelFormat();
                            log.info("Video Pixel Format: " + pixelFormat);

                            log.info("当前没数据重新读取");
                            continue;
                        }else if(frames.image!=null||(frames.keyFrame&&frames.image!=null)){ //
                            Mat opencvMat=bufferedImageToMat(converter.getBufferedImage(frames));
                            log.info("当前是关键帧+抓取到一帧{}:{}",pushInfo.getName(),videoUrl);
                            boolean flagYes=false;
                            for (int i = 0; i < nets.size(); i++) {
                                if(pushInfo.getIsBegin()==0){//前置条件

                                    if(i==0){
                                        identifyTypeAll identifyTypeAll=new  identifyTypeAll();
                                        if(nets.get(i).getModelType().equals("1")){
                                            if(!identifyTypeAll.detectObjects(pushInfo,opencvMat, nets.get(i).getNet(), claseeNames.get(i),tabAiModels.get(i))){
                                                log.warn("验证不通过");
                                                break;
                                            }

                                        }else{
                                            if(!identifyTypeAll.detectObjectsV5(pushInfo,opencvMat, nets.get(i).getNet(), claseeNames.get(i),tabAiModels.get(i))){
                                                log.warn("验证不通过");
                                                break;
                                            }

                                        }
                                        flagYes=true;
                                        continue;
                                    }
                                    if(i!=0&&flagYes){
                                        log.info("验证通过");
                                        if(nets.get(i).getModelType().equals("1")){
                                            detectObjects(pushInfo,opencvMat, nets.get(i).getNet(), claseeNames.get(i),tabAiModels.get(i));
                                        }else{
                                            detectObjectsV5(pushInfo,opencvMat, nets.get(i).getNet(), claseeNames.get(i),tabAiModels.get(i));
                                        }
                                    }

                                }else if(pushInfo.getIsBegin()!=0){
                                    log.info("不需要验证通过直接识别");
                                    if(nets.get(i).getModelType().equals("1")){
                                        detectObjects(pushInfo,opencvMat, nets.get(i).getNet(), claseeNames.get(i),tabAiModels.get(i));
                                    }else{
                                        detectObjectsV5(pushInfo,opencvMat, nets.get(i).getNet(), claseeNames.get(i),tabAiModels.get(i));
                                    }
                                }

                            }

                            opencvMat.release();
                        }else {
                         //   log.info("frames有数据但是图片没数据");
                            continue;
                        }
                    }catch (Exception ex){
                        ex.printStackTrace();

                    }
                }

                grabber.stop();
                grabber.release();
            } catch (Exception e) {
                e.printStackTrace();
            }


        }else{
            log.info("普通视频流开始");
            VideoCapture capture = new VideoCapture(pushInfo.getVideoURL(),Videoio.CAP_ANY);
            if (!capture.isOpened()) {
                log.info("Error: Unable to open video file.");
            }
            while (capture.read(frame)){
                long timestamp = (long) capture.get(Videoio.CAP_PROP_POS_MSEC);
                long nowTime=System.currentTimeMillis();
                long thistime=(nowTime-timestamp)/1000;
                log.info(thistime+"循环识别中！！！！！！！！！！！！！！！！！！！！！！！！！！！！"+pushInfo.getPushUrl());
                if(thistime>=5){
                    capture= new VideoCapture(pushInfo.getVideoURL());
                }
                for (int i = 0; i < nets.size(); i++) {
                    if(nets.get(i).getModelType().equals("1")){
                        detectObjects(pushInfo,frame, nets.get(i).getNet(), claseeNames.get(i),tabAiModels.get(i));
                    }else{
                        detectObjectsV5(pushInfo,frame, nets.get(i).getNet(), claseeNames.get(i),tabAiModels.get(i));
                    }


                }
                frame.release();
            }
            capture.release();
        }


        // 释放资源

//        Mat frame = Imgcodecs.imread(uploadpath+ File.separator +"wggggs_1710503896835.png");
//        for (int i = 0; i < nets.size(); i++) {
//            detectObjects(frame, nets.get(i), claseeNames.get(i));
//        }
    }

    private  boolean detectObjects(PushInfo pushInfo,Mat image, Net net, String className,TabAiModel tabAiModel) {

        List<String> classNames=null;
        try {
            classNames = Files.readAllLines(Paths.get(className));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // 读取输入图像
        Long a=System.currentTimeMillis();
        // 将图像传递给模型进行目标检测
        Mat blob = Dnn.blobFromImage(image, 1.0 / 255, new Size(416, 416), new Scalar(0), true, false);
        net.setInput(blob);
        // 将图像传递给模型进行目标检测
        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = net.getUnconnectedOutLayersNames();
        net.forward(result, outBlobNames);

        // 处理检测结果
        float confThreshold = 0.56f;
        List<Rect2d> boundingBoxes = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();
        for (Mat level : result) {
            for (int i = 0; i < level.rows(); ++i) {
                Mat row = level.row(i);
                Mat scores = level.row(i).colRange(5, level.cols());
                Core.MinMaxLocResult minMaxLocResult = Core.minMaxLoc(scores);
                Point classIdPoint = minMaxLocResult.maxLoc;
                double confidence = row.get(0, 4)[0];
                if (confidence > confThreshold) {
                    //    log.info("classIdPoint"+ classIdPoint);
                    //    log.info("classIdPointx"+ classIdPoint.x);
                    classIds.add((int) classIdPoint.x); //记录标签下标
                    double centerX = row.get(0, 0)[0] * image.cols();
                    double centerY = row.get(0, 1)[0] * image.rows();
                    double width = row.get(0, 2)[0] * image.cols();
                    double height = row.get(0, 3)[0] * image.rows();
                    double left = centerX - width / 2;
                    double top = centerY - height / 2;
                    // 绘制边界框
                    Rect2d rect = new Rect2d(left, top, width, height);
                    boundingBoxes.add(rect);
                    confidences.add((float)confidence);
                }
            }
        }

        // 执行非最大抑制，消除重复的边界框
        MatOfRect2d boxes = new MatOfRect2d(boundingBoxes.toArray(new Rect2d[0]));
        MatOfFloat confidencesMat = new MatOfFloat();
        confidencesMat.fromList(confidences);
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes, confidencesMat, confThreshold, 0.4f, indices);

        if(indices.empty()){
            log.info("类别下标啊"+"未识别到内容");
            return false;
        }

        int[]   indicesArray= indices.toArray();
        // 获取保留的边界框

        log.info(confidences.size()+"类别下标啊"+indicesArray.length);
        // 在图像上绘制保留的边界框
        int c=0;
        for (int idx : indicesArray) {
            // 添加类别标签
            log.info("当前有多少"+confidences.get(idx));
            Integer ab=classIds.get(idx);
            String name=classNames.get(ab);
            TabAiBase aiBase =VideoSendReadCfg.map.get(name);
            if(aiBase==null){
                aiBase.setChainName(name);
            }
            Rect2d box = boundingBoxes.get(idx);
            Imgproc.rectangle(image, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height),CommonColors(c), 2);

            log.info( aiBase.getChainName()+"类别下标"+ab);
             image = AIModelYolo3.addChineseText(image, aiBase.getChainName(),new Point(box.x, box.y - 5),CommonColors(c));
          //  Imgproc.putText(image, classNames.get(ab), new Point(box.x, box.y - 5), Core.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
            c++;
        }

        String savepath=uploadpath + File.separator + "temp" + File.separator;

        String  saveName=pushInfo.getPushId();
        if(StringUtils.isNotBlank(saveName)){
            saveName=savepath+saveName+".jpg";
        }else{
            saveName=savepath+System.currentTimeMillis()+".jpg";
        }
        log.info("存储地址{}",saveName);
        File imageFile = new File(saveName);
        if (imageFile.exists()) {
            imageFile.delete();
        }
        Imgcodecs.imwrite(saveName, image);
       String base64Img=base64Image(saveName);
       //组装参数
       pushEntity push=new  pushEntity();
       push.setVideo(pushInfo.getVideoURL());
       push.setType("图片");
       push.setCameraUrl(pushInfo.getVideoURL());
       push.setAlarmPicData(base64Img);
       push.setTime(System.currentTimeMillis()+"");
       push.setModelId(tabAiModel.getId());
       push.setIndexCode(pushInfo.getIndexCode());
       push.setModelName(tabAiModel.getAiName());
        JSONObject ob=null;
        try {
            Long b=System.currentTimeMillis();
            int endTime= (int) ((b-LastTime)/1000);
            if(LastTime==0L){
                log.info("当前时间未赋值："+endTime);
                LastTime=b;
            }else if(endTime>=pushInfo.getTime()){
                LastTime=b;
                log.info("当前时间频率赋值："+endTime);
            }else if(endTime<pushInfo.getTime()){
                log.info("当前时间小于间隔："+endTime);
                return  false;
            }
            ob=RestUtil.post(pushInfo.getPushUrl(), (JSONObject) JSONObject.toJSON(push));
            // mqtt
            log.info("消耗时间："+(b-a));
            log.info("返回内容："+ob);
            LastTime=b;



       }catch (Exception ex){
            log.info("连接失败");
            return false;

       }


       return true;
    }


    private  boolean detectObjectsV5(PushInfo pushInfo,Mat image, Net net, String className,TabAiModel tabAiModel) {

        List<String> classNames=null;
        try {
            classNames = Files.readAllLines(Paths.get(className));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // 读取输入图像
        Long a=System.currentTimeMillis();
        // 将图像传递给模型进行目标检测
        Mat blob = Dnn.blobFromImage(image, 1.0 / 255, new Size(640, 640), new Scalar(0), true, false);
        net.setInput(blob);
        // 将图像传递给模型进行目标检测
        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = net.getUnconnectedOutLayersNames();
        net.forward(result, outBlobNames);

        // 处理检测结果
        float confThreshold = 0.56f;
        float nmsThreshold = 0.45f;
        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();

        for (Mat output : result) {
            int dims = output.dims();
            int index = (int) output.size(0);
            int rows = (int) output.size(1);
            int cols = (int) output.size(2);
            //
            // Dims: 3, Rows: 25200, Cols: 8 row,Mat [ 1*25200*8*CV_32FC1, isCont=true, isSubmat=false, nativeObj=0x28dce2da990, dataAddr=0x28dd0ebc640 ]index:1
        //    log.info("Dims: " + dims + ", Rows: " + rows + ", Cols: " + cols+" row,"+output.row(0)+"index:"+index);
            Mat detectionMat = output.reshape(1, output.size(1));

            for (int i = 0; i < detectionMat.rows(); i++) {
                Mat detection = detectionMat.row(i);
                Mat scores = detection.colRange(5, cols);
                Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                float confidence = (float)detection.get(0, 4)[0];
                Point classIdPoint = minMaxResult.maxLoc;

                if (confidence > confThreshold) {
                    float centerX = (float)detection.get(0, 0)[0];
                    float centerY = (float)detection.get(0, 1)[0];
                    float width = (float)detection.get(0, 2)[0];
                    float height = (float)detection.get(0, 3)[0];

                    float left = centerX - width / 2;
                    float top = centerY - height / 2;

                    classIds.add((int)classIdPoint.x);
                    confidences.add(confidence);
                    boxes2d.add(new Rect2d(left, top, width, height));
                    //  System.out.println("识别到了");
                }
            }
        }

        if(confidences.size()<=0){
            log.warn(pushInfo.getName()+":当前未检测到内容");
            return  false ;
        }
        // 执行非最大抑制，消除重复的边界框
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);
        log.info("confidences.size{}",confidences.size());
        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            System.out.println("不为空");
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }

        int[]   indicesArray= indices.toArray();
        // 获取保留的边界框

        log.info(confidences.size()+"类别下标啊"+indicesArray.length);
        // 在图像上绘制保留的边界框
        int c=0;
        String  audioText="";
        Integer warnNumber=0;
        String  warnText="";
        String  warnName="";
        for (int idx : indicesArray) {
            // 添加类别标签
            Rect2d box = boxes2d.get(idx);
            Integer ab=classIds.get(idx);
            String name=classNames.get(ab);
            float conf = confidences.get(idx);
            double x=box.x;
            double y=box.y;
            double width=box.width*((double)image.cols()/640);
            double height=box.height*((double)image.rows()/640);
            double xzb=x*((double)image.cols()/640);
            double yzb=y*((double)image.rows()/640);

            TabAiBase aiBase =VideoSendReadCfg.map.get(name);
            if(aiBase==null){
                aiBase=new TabAiBase();
                aiBase.setChainName(name);

            }

            audioText +=aiBase.getRemark()+aiBase.getSpaceOne();
            warnNumber+=aiBase.getSpaceTwo()==null?0:aiBase.getSpaceTwo();
            warnText +=StringUtils.isEmpty(aiBase.getRemark())==true?"":aiBase.getRemark();
            warnName+=aiBase.getChainName()+",";
           // Imgproc.rectangle(image, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height),CommonColors(c), 2);
            Imgproc.rectangle(image,
                    new Point(xzb, yzb),
                    new Point(xzb + width, yzb+ height),
                    CommonColors(c), 2);
            log.info( "类别下标"+ab);
            image = AIModelYolo3.addChineseText(image, aiBase.getChainName()+conf,  new Point(xzb, yzb),CommonColors(c));
            //  Imgproc.putText(image, classNames.get(ab), new Point(box.x, box.y - 5), Core.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
            c++;
        }

        String savepath=uploadpath + File.separator + "push" + File.separator;
        File file=new File(savepath);
        if(!file.exists()){
            file.mkdirs();
        }
        System.out.println();
        String  saveName=pushInfo.getPushId();
        if(StringUtils.isNotBlank(saveName)){
            saveName=savepath+saveName+".jpg";
        }else{
            saveName=savepath+System.currentTimeMillis()+".jpg";
        }
        log.info("存储地址{}",saveName);
        File imageFile = new File(saveName);
        if (imageFile.exists()) {
            imageFile.delete();
        }
        Imgcodecs.imwrite(saveName, image);
        String base64Img=base64Image(saveName);
        //组装参数
        pushEntity push=new  pushEntity();
        push.setCameraName(pushInfo.getName());
        push.setVideo(pushInfo.getVideoURL());
        push.setType("图片");
        push.setCameraUrl(pushInfo.getVideoURL());
        push.setAlarmPicData(base64Img);
        push.setTime(System.currentTimeMillis()+"");
        push.setModelId(tabAiModel.getAiName());
        push.setIndexCode(pushInfo.getIndexCode());
        push.setModelName(warnName);
        push.setAiNumber(warnNumber);
        push.setModelText(warnText);
        JSONObject ob=null;
        try {
            Long b=System.currentTimeMillis();
            int endTime= (int) ((b-LastTime)/1000);
            if(LastTime==0L){
                log.info("当前时间未赋值："+endTime);
                LastTime=b;
            }else if(endTime>=pushInfo.getTime()){
                LastTime=b;
                log.info("当前时间频率赋值："+endTime);
            }else if(endTime<pushInfo.getTime()){
                log.info("当前时间小于间隔："+endTime);
                return  false;
            }
            if(pushInfo.getPushStatic()==0){
                ob=RestUtil.post(pushInfo.getPushUrl(), (JSONObject) JSONObject.toJSON(push));
                // mqtt
                log.info("消耗时间："+(b-a));
                log.info("返回内容："+ob);
            }else{
                log.info("不推送第三方结果：");
            }

            if(pushInfo.getAudioStatic()==0){
                log.info("语音播报："+audioText);
                String token= getToken(pushInfo.getAudioId());
                postAudioText(token,pushInfo.getAudioId(),audioText);
            }else{
                log.info("语音不播报：");
            }

            LastTime=b;



        }catch (Exception ex){
            ex.printStackTrace();
            log.info("连接失败");
            return false;

        }


        return true;
    }
}
