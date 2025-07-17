package org.jeecg.modules.demo.video.util;

import org.bytedeco.javacv.*;
import org.bytedeco.ffmpeg.global.avcodec;
import lombok.extern.slf4j.Slf4j;


/***
 * wg
 */
@Slf4j
public class AlarmVideoRecorder {
    private final int width;
    private final int height;
    private final double frameRate;
    private final int audioChannels;
    private FFmpegFrameRecorder recorder;
    private String outputPath;
    private long recordEndTime = 0;
    private boolean isRecording = false;

    public AlarmVideoRecorder(int width, int height, double frameRate, int audioChannels) {
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.audioChannels = audioChannels;
    }

    public synchronized void startRecording(String outputPath, long durationMillis) {
        long now = System.currentTimeMillis();
        if (!isRecording) {
            this.outputPath = outputPath;
            recorder = new FFmpegFrameRecorder(outputPath, width, height, audioChannels);
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFormat("mp4");
            recorder.setFrameRate(frameRate);
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);

            try {
                recorder.start();
                isRecording = true;
                log.info("[录像开始] {}", outputPath);
            } catch (Exception e) {
                log.error("启动录像失败: {}", e.getMessage());
                isRecording = false;
            }
        }
        this.recordEndTime = now + durationMillis;
    }

    public synchronized void pushFrame(Frame frame) {
        if (isRecording && recorder != null && frame != null) {
            try {
                recorder.record(frame);
            } catch (Exception e) {
                log.error("写入视频帧失败: {}", e.getMessage());
            }
        }
        checkStop();
    }

    public synchronized void pushSample(Frame audioSample) {
        if (isRecording && recorder != null && audioSample != null) {
            try {
                recorder.recordSamples(audioSample.samples);
            } catch (Exception e) {
                log.error("写入音频失败: {}", e.getMessage());
            }
        }
        checkStop();
    }

    private void checkStop() {
        if (isRecording && System.currentTimeMillis() >= recordEndTime) {
            stop();
        }
    }

    public synchronized void stop() {
        if (recorder != null) {
            try {
                recorder.stop();
                recorder.release();
                log.info("[录像结束] {}", outputPath);
            } catch (Exception e) {
                log.error("停止录像失败: {}", e.getMessage());
            } finally {
                recorder = null;
                isRecording = false;
            }
        }
    }

    public synchronized boolean isRecording() {
        return isRecording;
    }
}
