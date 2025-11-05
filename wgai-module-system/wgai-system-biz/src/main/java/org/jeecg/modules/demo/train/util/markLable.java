package org.jeecg.modules.demo.train.util;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.train.entity.TabModelTry;
import org.jeecg.modules.demo.train.entity.TabTrainPython;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * @author wggg
 * @date 2025/1/14 15:43
 */
@Slf4j
public class markLable {

    // 设定类别

    private static  String[] sets = {"train", "test", "val"};
//    private static  String[] classes = {"closedoor", "opendoor"};
//    private static  String annotationsDir = "/data/Annotations";  // 存放xml的路径
//    private static  String labelsDir = "/data/labels";  // 存放labels文件的路径
//    private static  String imageSetsDir = "/data/ImageSets";  // 存放txt文件路径
//
//
//    public static void setClasses(String[] classes) {
//        markLable.classes = classes;
//    }
//
//    public static void setSets(String[] sets) {
//        markLable.sets = sets;
//    }
//
//    public static void setAnnotationsDir(String annotationsDir) {
//        markLable.annotationsDir = annotationsDir;
//    }
//
//    public static void setLabelsDir(String labelsDir) {
//        markLable.labelsDir = labelsDir;
//    }
//
//    public static void setImageSetsDir(String imageSetsDir) {
//        markLable.imageSetsDir = imageSetsDir;
//    }

    // 归一化操作 (convert函数)
    public static double[] convert(int w, int h, double[] box) {
        double dw = 1.0 / w;
        double dh = 1.0 / h;
        double x = (box[0] + box[1]) / 2.0;
        double y = (box[2] + box[3]) / 2.0;
        double width = box[1] - box[0];
        double height = box[3] - box[2];
        return new double[]{x * dw, y * dh, width * dw, height * dh};
    }

    public static void main(String[] args) throws Exception {

//        classes=new String[]{"test"};
//        annotationsDir="F:\\JAVAAI\\windows_v1.5.1";
//        labelsDir="F:\\JAVAAI\\windows_v1.5.1";
//        convertAnnotation("00001");
    }
    // 解析并转换XML文件
    public static void convertAnnotation(String imageId,String annotationsDir,String labelsDir,String [] classes ) throws Exception {
        // 打开xml文件
        File xmlFile = new File(annotationsDir + File.separator + imageId + ".xml");
        File txtFile = new File(labelsDir + File.separator + imageId + ".txt");

        if (!xmlFile.exists()) {
           log.info("XML file not found: " + xmlFile.getPath());
            return;
        }

        // 准备输出文件
        BufferedWriter writer = new BufferedWriter(new FileWriter(txtFile));

        // 解析XML
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlFile);
        doc.getDocumentElement().normalize();

        // 获取图像尺寸
        NodeList sizeNodeList = doc.getElementsByTagName("size");
        if (sizeNodeList.getLength() > 0) {
            Element sizeElement = (Element) sizeNodeList.item(0);
            int width = Integer.parseInt(sizeElement.getElementsByTagName("width").item(0).getTextContent());
            int height = Integer.parseInt(sizeElement.getElementsByTagName("height").item(0).getTextContent());

            // 遍历每个object节点
            NodeList objectNodeList = doc.getElementsByTagName("object");
            for (int i = 0; i < objectNodeList.getLength(); i++) {
                Element objectElement = (Element) objectNodeList.item(i);

                // 获取类别名称和标记
                String className = objectElement.getElementsByTagName("name").item(0).getTextContent();

                // 检查difficult标签是否存在
                String difficult = "0";
                NodeList difficultNodes = objectElement.getElementsByTagName("difficult");
                if (difficultNodes.getLength() > 0) {
                    difficult = difficultNodes.item(0).getTextContent();
                }

                // 获取类型
                String type = "bndbox"; // 默认类型
                NodeList typeNodes = objectElement.getElementsByTagName("type");
                if (typeNodes.getLength() > 0) {
                    type = typeNodes.item(0).getTextContent();
                }

                if (Arrays.asList(classes).contains(className) && !difficult.equals("1")) {
                    int classId = Arrays.asList(classes).indexOf(className);

                    // 根据类型处理不同的标注格式
                    if (type.equals("polygon")) {
                        log.info("处理多边形！！！！！！！！！！！");
                        // 处理多边形
                        handlePolygon(objectElement, classId, width, height, writer);
                    } else if (type.equals("control")) {
                        log.info("处理控制点！！！！！！！！！！！");
                        // 处理控制点
                        handleControlPoint(objectElement, classId, width, height, writer);
                    } else {
                        log.info("处理矩形边界框！！！！！！！！！！！");
                        // 处理矩形边界框(原有逻辑)
                        handleBoundingBox(objectElement, classId, width, height, writer);
                    }
                }
            }
        }

        writer.close();
    }

    // 处理矩形边界框
    private static void handleBoundingBox(Element objectElement, int classId, int width, int height, BufferedWriter writer) throws Exception {
        NodeList bndboxNodes = objectElement.getElementsByTagName("bndbox");
        if (bndboxNodes.getLength() > 0) {
            Element bndbox = (Element) bndboxNodes.item(0);
            double xmin = Double.parseDouble(bndbox.getElementsByTagName("xmin").item(0).getTextContent());
            double xmax = Double.parseDouble(bndbox.getElementsByTagName("xmax").item(0).getTextContent());
            double ymin = Double.parseDouble(bndbox.getElementsByTagName("ymin").item(0).getTextContent());
            double ymax = Double.parseDouble(bndbox.getElementsByTagName("ymax").item(0).getTextContent());

            // 归一化坐标
            double[] box = convert(width, height, new double[]{xmin, xmax, ymin, ymax});

            // 写入文件 (YOLO格式: class_id center_x center_y width height)
            writer.write(classId + " " + box[0] + " " + box[1] + " " + box[2] + " " + box[3] + "\n");
        }
    }

    // 处理多边形
    private static void handlePolygon(Element objectElement, int classId, int width, int height, BufferedWriter writer) throws Exception {
        NodeList polygonNodes = objectElement.getElementsByTagName("polygon");
        if (polygonNodes.getLength() > 0) {
            Element polygon = (Element) polygonNodes.item(0);
            NodeList ptNodes = polygon.getElementsByTagName("pt");

            StringBuilder line = new StringBuilder();
            line.append(classId);

            // 遍历所有点,归一化坐标
            for (int j = 0; j < ptNodes.getLength(); j++) {
                Element pt = (Element) ptNodes.item(j);
                double x = Double.parseDouble(pt.getElementsByTagName("x").item(0).getTextContent());
                double y = Double.parseDouble(pt.getElementsByTagName("y").item(0).getTextContent());

                // 归一化到0-1范围
                double normX = x / width;
                double normY = y / height;

                line.append(" ").append(normX).append(" ").append(normY);
            }

            writer.write(line.toString() + "\n");
        }
    }

    // 处理控制点
    private static void handleControlPoint(Element objectElement, int classId, int width, int height, BufferedWriter writer) throws Exception {
        NodeList pointNodes = objectElement.getElementsByTagName("point");
        if (pointNodes.getLength() > 0) {
            Element point = (Element) pointNodes.item(0);
            double x = Double.parseDouble(point.getElementsByTagName("x").item(0).getTextContent());
            double y = Double.parseDouble(point.getElementsByTagName("y").item(0).getTextContent());

            // 归一化到0-1范围
            double normX = x / width;
            double normY = y / height;

            // 写入文件 (格式: class_id x y)
            writer.write(classId + " " + normX + " " + normY + "\n");
        }
    }
    public static void sendLable(TabTrainPython python, TabModelTry tabModelTry){
        log.info("【获取的路径】{}",python.getPyPath());

        String annotationsDir=python.getPyPath()+"/data/Annotations";
        String labelsDir=python.getPyPath()+"/data/labels";
        String imageSetsDir=python.getPyPath()+"/data/ImageSets";
        String[] classes =tabModelTry.getTxtInfo().split(",");

        log.info("【xml文件夹目录】{}",annotationsDir);
        log.info("【labels文件夹目录】{}",labelsDir);
        log.info("【ImageSets文件夹目录】{}",imageSetsDir);
        try{
        // 创建文件夹
            if (!Files.exists(Paths.get(labelsDir))) {
                Files.createDirectories(Paths.get(labelsDir));
            }
        // 遍历每个数据集：train, test, val
        for (String set : sets) {
            // 读取ImageSets/Main中的对应数据集文件
            File imageSetFile = new File(imageSetsDir + File.separator + set + ".txt");
            List<String> imageIds = Files.readAllLines(imageSetFile.toPath());

            // 创建对应的txt文件
            File listFile = new File(python.getPyPath()+"/data" + File.separator + set + ".txt");
            BufferedWriter writer = new BufferedWriter(new FileWriter(listFile));

            // 遍历每个图片id，转换为对应的label并写入路径
            for (String imageId : imageIds) {
                // 写入图片路径
                writer.write(python.getPyPath()+"/data/images/" + imageId + ".jpg\n");
                // 转换annotation
                convertAnnotation(imageId,annotationsDir,labelsDir,classes);
            }

            writer.close();
        }
        }catch (Exception ex){
                ex.printStackTrace();
        }

    }

}
