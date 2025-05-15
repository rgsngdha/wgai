package org.jeecg.modules.tab.AIModel.identify;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.util.RestUtil;
import org.jeecg.modules.demo.tab.entity.PushInfo;
import org.jeecg.modules.demo.tab.entity.TabAiBase;
import org.jeecg.modules.tab.AIModel.AIModelYolo3;
import org.jeecg.modules.tab.AIModel.VideoSendReadCfg;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.entity.pushEntity;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.utils.Converters;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.jeecg.modules.demo.audio.util.audioSend.getToken;
import static org.jeecg.modules.demo.audio.util.audioSend.postAudioText;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.CommonColors;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.base64Image;

/**
 * 多类型识别内容
 * @author wggg
 * @date 2025/3/19 11:36
 */

@Slf4j
public class identifyTypeAll {

    //v3 验证是否通过
    public  boolean detectObjects(PushInfo pushInfo, Mat image, Net net, String className, TabAiModel tabAiModel) {
        boolean flag=false;
        try {


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

        for (int idx : indicesArray) {
            // 添加类别标签
            log.info("当前有多少"+confidences.get(idx));
            Integer ab=classIds.get(idx);
            String name=classNames.get(ab);
            TabAiBase aiBase = VideoSendReadCfg.map.get(name);
            if(aiBase==null){
                aiBase.setChainName(name);
            }
            if(aiBase.getChainName().equals(pushInfo.getBeginName())){
                flag=true;
                break;
            }
        }

        }catch (Exception ex){

            return false;
        }


        return flag;
    }


    //v5 v8 V10 验证是否通过
    public  boolean detectObjectsV5(PushInfo pushInfo,Mat image, Net net, String className,TabAiModel tabAiModel) {
        boolean flag=false;
        try {


            List<String> classNames = null;
            try {
                classNames = Files.readAllLines(Paths.get(className));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            // 读取输入图像
            Long a = System.currentTimeMillis();
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
                    float confidence = (float) detection.get(0, 4)[0];
                    Point classIdPoint = minMaxResult.maxLoc;

                    if (confidence > confThreshold) {
                        float centerX = (float) detection.get(0, 0)[0];
                        float centerY = (float) detection.get(0, 1)[0];
                        float width = (float) detection.get(0, 2)[0];
                        float height = (float) detection.get(0, 3)[0];

                        float left = centerX - width / 2;
                        float top = centerY - height / 2;

                        classIds.add((int) classIdPoint.x);
                        confidences.add(confidence);
                        boxes2d.add(new Rect2d(left, top, width, height));
                        //  System.out.println("识别到了");
                    }
                }
            }

            if (confidences.size() <= 0) {
                log.warn(pushInfo.getName() + ":当前未检测到内容");
                return false;
            }
            // 执行非最大抑制，消除重复的边界框
            MatOfRect2d boxes_mat = new MatOfRect2d();
            boxes_mat.fromList(boxes2d);
            log.info("confidences.size{}", confidences.size());
            MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
            MatOfInt indices = new MatOfInt();
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
            if (!boxes_mat.empty() && !confidences_mat.empty()) {
                System.out.println("不为空");
                Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
            }

            int[] indicesArray = indices.toArray();
            // 获取保留的边界框

            log.info(confidences.size() + "类别下标啊" + indicesArray.length);
            // 在图像上绘制保留的边界框
            int c = 0;

            for (int idx : indicesArray) {
                // 添加类别标签
                Integer ab = classIds.get(idx);
                String name = classNames.get(ab);

                TabAiBase aiBase = VideoSendReadCfg.map.get(name);
                if (aiBase == null) {
                    aiBase = new TabAiBase();
                    aiBase.setChainName(name);

                }
                log.info("当前类别{}",name);
                if (aiBase.getChainName().equals(pushInfo.getBeginName())) {
                    flag = true;
                    break;
                }
            }
        }catch (Exception ex){
            return false;
        }
        return flag;
    }
}
