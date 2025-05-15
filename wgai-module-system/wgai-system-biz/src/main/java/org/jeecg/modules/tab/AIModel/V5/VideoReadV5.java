package org.jeecg.modules.tab.AIModel.V5;

import lombok.extern.slf4j.Slf4j;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.modules.tab.AIModel.VideoFrameReader;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Arrays;
import java.util.List;

import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;

/***
 * 读取状态
 */
@Slf4j
public class VideoReadV5 implements Runnable {

    private RedisTemplate redisTemplate;
    public String videoUrl;

    public String userId;
    public VideoReadV5(String videoUrl, RedisTemplate redisTemplate, String userId){
        this.videoUrl=videoUrl;
        this.redisTemplate=redisTemplate;
        this.userId=userId;
    }
    @Override
    public void run() {

        if(videoUrl.indexOf("rtsp")>-1){ //当前为rtsp
            log.info("当前为rtsp");
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoUrl);
            try {
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
                grabber.start(); // 开始读取视频
                log.info("读取成功视频：{}",videoUrl);
            } catch (FFmpegFrameGrabber.Exception e) {
                throw new RuntimeException(e);
            }
            Java2DFrameConverter converter = new Java2DFrameConverter();
            MapTime mapTime=new MapTime();
            int c=0;
            try {


                while (true) {
                    Boolean flag= (Boolean) redisTemplate.opsForValue().get(videoUrl+"V5"+userId);
                  //  log.info("开始推理{}读取视频内容",flag);
                    if(!flag){
                        grabber.release();
                        break;
                    }
                    Frame frames = grabber.grab();
                    if(frames==null){
                        grabber.stop();
                        grabber.release();
                        grabber = new FFmpegFrameGrabber(videoUrl);
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
                        grabber.start(); // 开始读取视频
                        log.info("当前没数据重新读取");
                        continue;
                    }
                    if(frames.image!=null&&frames.keyFrame) {
                        Mat frame=bufferedImageToMat(converter.getBufferedImage(frames));
                        long timestamp = (long) grabber.getTimestamp();

                        int StartTime= VideoSendReadCfgV5.StartTime;
                        if(StartTime==0){
                            VideoSendReadCfgV5.StartTime= (int) timestamp;
                        }else if(timestamp<=StartTime){
                            log.info("【按帧读取】跳出本次循环{}-当前跳跃帧{}",StartTime,timestamp);
                            continue;
                        }else{
                            VideoSendReadCfgV5.StartTime= (int) timestamp;
                            log.info("【按帧读取】赋值最大值{}-跳出本次循环{}-当前跳跃帧{}",StartTime-timestamp,StartTime,timestamp);
                        }

                        Integer timeStart= (Integer) redisTemplate.opsForValue().get(videoUrl+"timeV5"+userId);
                        if(timeStart!=null){
                            if(timeStart>timestamp){
                                continue;
                            }
                        }

                        Object[] array = VideoSendReadCfgV5.matlist.toArray();
                        if(array.length>0){
                            int matlistSize=array.length;
                            mapTime= (MapTime) array[matlistSize-1];
                            log.warn("【读取最新帧】-当前时间{},队列时间{}",timestamp,mapTime.times);
                            if(mapTime.times<timestamp){
                                if(matlistSize==10){
                                    VideoSendReadCfgV5.matlist.poll();
                                }
                                mapTime.setTimes((int) timestamp);
                                mapTime.setMat(frame);
                                try {
                                    //     redisTemplate.opsForValue().set("test"+timestamp,timestamp);
                                    log.error("【读取最新帧】-更替最新存放时间戳！！！->{}--{}",timestamp, VideoFrameReaderV5.millisecondsToHours(timestamp));
                                    VideoSendReadCfgV5.matlist.offer(mapTime);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }else{
                            // redisTemplate.opsForValue().set("test"+timestamp,timestamp);
                            log.warn("【读取最新帧】-存放时间戳{}--{}",timestamp,VideoFrameReaderV5.millisecondsToHours(timestamp));
                            mapTime.setTimes((int) timestamp);
                            mapTime.setMat(frame);
                            try {
                                VideoSendReadCfgV5.matlist.offer(mapTime);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                        c++;
                        log.warn("【读取读取帧】-当前时间戳2:{}--{}当前线程数22222:{}",timestamp,VideoFrameReaderV5.millisecondsToHours(timestamp));
                    }

                }
                grabber.stop();
                grabber.release();
            }catch (Exception ex){


                ex.printStackTrace();
                try {
                    grabber.stop();
                    grabber.release();
                } catch (Exception e) {
                    log.info("解析失败");
                }

            }

        }else{
            VideoCapture capture = new VideoCapture(videoUrl);
            if (!capture.isOpened()) {
                System.out.println("Error: Could not open video stream.");
                return;
            }
            // 尝试调整缓冲区大小
            //  capture.set(Videoio.CAP_PROP_BUFFERSIZE, 1); // 设置缓冲区大小为1帧

            Mat frame = new Mat();
            MapTime mapTime=new MapTime();
            int c=0;
            while (capture.read(frame)) {

                Boolean flag= (Boolean) redisTemplate.opsForValue().get(videoUrl+"V5"+userId);
                log.info("开始推理{}读取视频内容",flag);
                if(!flag){
                    capture.release();
                    break;
                }
                long timestamp = (long) capture.get(Videoio.CAP_PROP_POS_MSEC);
                int StartTime= VideoSendReadCfgV5.StartTime;
                if(StartTime==0){
                    VideoSendReadCfgV5.StartTime= (int) timestamp;
                }else if(timestamp<=StartTime){
                    log.info("【按帧读取】跳出本次循环{}-当前跳跃帧{}",StartTime,timestamp);
                    continue;
                }else{
                    VideoSendReadCfgV5.StartTime= (int) timestamp;
                    log.info("【按帧读取】赋值最大值{}-跳出本次循环{}-当前跳跃帧{}",StartTime-timestamp,StartTime,timestamp);
                }

                Integer timeStart= (Integer) redisTemplate.opsForValue().get(videoUrl+"timeV5"+userId);
                if(timeStart!=null){
                    if(timeStart>timestamp){
                        continue;
                    }
                }

                Object[] array = VideoSendReadCfgV5.matlist.toArray();
                if(array.length>0){
                    int matlistSize=array.length;
                    mapTime= (MapTime) array[matlistSize-1];
                    log.warn("【读取最新帧】-当前时间{},队列时间{}",timestamp,mapTime.times);
                    if(mapTime.times<timestamp){
                        if(matlistSize==10){
                            VideoSendReadCfgV5.matlist.poll();
                        }
                        mapTime.setTimes((int) timestamp);
                        mapTime.setMat(frame);
                        try {
                            //     redisTemplate.opsForValue().set("test"+timestamp,timestamp);
                            log.error("【读取最新帧】-更替最新存放时间戳！！！->{}--{}",timestamp, VideoFrameReaderV5.millisecondsToHours(timestamp));
                            VideoSendReadCfgV5.matlist.offer(mapTime);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }else{
                    // redisTemplate.opsForValue().set("test"+timestamp,timestamp);
                    log.warn("【读取最新帧】-存放时间戳{}--{}",timestamp,VideoFrameReaderV5.millisecondsToHours(timestamp));
                    mapTime.setTimes((int) timestamp);
                    mapTime.setMat(frame);
                    try {
                        VideoSendReadCfgV5.matlist.offer(mapTime);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                c++;
                log.warn("【读取读取帧】-当前时间戳2:{}--{}当前线程数22222:{}",timestamp,VideoFrameReaderV5.millisecondsToHours(timestamp));
            }
            capture.release();
        }

    }

    public static void main(String[] args) {
        List<String> classes = Arrays.asList("person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush");
        for (int i = 0; i <classes.size() ; i++) {
            System.out.println(classes.get(i));
        }
    }
}
