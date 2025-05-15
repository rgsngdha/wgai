package org.jeecg.modules.demo.szr.util;

import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author wggg
 * @date 2025/4/10 15:01
 */
public class szrUtil {

    public static void main(String[] args) {


        int imageCount = 115;                               // 图片数量



        String imagePattern = "F:\\JAVAAI\\audio\\数字人\\zhangwei\\pic\\%08d.png";  // 帧图路径

        double frameRate =15.38461538;
        String audioPath="D:\\opt\\upFiles\\1746494451564.wav";
        String outputPath="F:\\JAVAAI\\audio\\hecheng1.mp4";

        try {
            merge(imagePattern, imageCount, audioPath, outputPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
//        ProcessBuilder pb = new ProcessBuilder(
//                "ffmpeg",
//                "-stream_loop", "-1",
//                "-r", String.valueOf(frameRate),
//                "-start_number", "0",
//                "-i", imagePattern,
//                "-i", audioPath,
//                "-c:v", "libx264",
//                "-pix_fmt", "yuv420p",
//                "-c:a", "aac",
//                "-b:a", "192k",
//                "-shortest",
//                outputPath
//        );
//
//        pb.inheritIO(); // 打印 FFmpeg 执行过程日志（可选）
//
//        try {
//            Process process = pb.start();
//            int exitCode = process.waitFor();
//            if (exitCode == 0) {
//                System.out.println("✅ 视频合成成功：" + outputPath);
//            } else {
//                System.err.println("❌ FFmpeg 执行失败，退出码：" + exitCode);
//            }
//        } catch (IOException | InterruptedException e) {
//            e.printStackTrace();
//        }
    }

    // 简化：用音量作为判断标准（帧率 = 25fps）
    public static List<Boolean> analyzeAudioRhythm(String audioPath, int fps) throws Exception {
        List<Boolean> openMouth = new ArrayList<>();

        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new File(audioPath));
        AudioFormat format = audioInputStream.getFormat();
        byte[] audioBytes =readAllBytes(audioInputStream);

        int frameSize = (int) (format.getFrameRate() / fps);
        int bytesPerSample = format.getFrameSize();

        for (int i = 0; i < audioBytes.length; i += frameSize * bytesPerSample) {
            double sum = 0;
            for (int j = 0; j < frameSize * bytesPerSample && i + j < audioBytes.length; j += 2) {
                short sample = (short) ((audioBytes[i + j + 1] << 8) | (audioBytes[i + j] & 0xff));
                sum += Math.abs(sample);
            }
            double avg = sum / frameSize;
            openMouth.add(avg > 500);  // 简单阈值
        }

        return openMouth;
    }
    public static byte[] readAllBytes(AudioInputStream audioInputStream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] temp = new byte[4096];
        int bytesRead;
        while ((bytesRead = audioInputStream.read(temp)) != -1) {
            buffer.write(temp, 0, bytesRead);
        }
        return buffer.toByteArray();
    }


    /**
     * 获取音频时长（单位：秒）
     */
    public static double getAudioDuration(String audioPath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe",
                "-i", audioPath,
                "-show_entries", "format=duration",
                "-v", "quiet",
                "-of", "csv=p=0"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line = reader.readLine();
            if (line != null) {
                return Double.parseDouble(line.trim());
            }
        }

        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy();
            throw new RuntimeException("FFprobe 超时未返回音频时长");
        }

        throw new RuntimeException("无法获取音频时长");
    }

    /**
     * 合成视频
     */
    public static void merge(String imagePattern,
                             int imageCount,
                             String audioPath,
                             String outputPath) throws IOException, InterruptedException {
        double duration = getAudioDuration(audioPath);
        double fps = imageCount / duration;

        System.out.println("音频时长：" + duration + " 秒");
        System.out.println("自动计算帧率：" + fps + " fps");

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-r", String.valueOf(fps),
                "-start_number", "1",
                "-i", imagePattern,
                "-i", audioPath,
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "192k",
                "-shortest",
                outputPath
        );

        pb.inheritIO(); // 打印 FFmpeg 输出

        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            System.out.println("✅ 视频合成成功：" + outputPath);
        } else {
            System.err.println("❌ FFmpeg 执行失败，退出码：" + exitCode);
        }
    }
}
