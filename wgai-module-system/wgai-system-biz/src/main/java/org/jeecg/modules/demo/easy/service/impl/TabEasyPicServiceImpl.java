package org.jeecg.modules.demo.easy.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.demo.easy.entity.TabEasyPic;
import org.jeecg.modules.demo.easy.mapper.TabEasyPicMapper;
import org.jeecg.modules.demo.easy.service.ITabEasyPicService;
import org.jeecg.modules.demo.train.entity.TabModelTry;
import org.jeecg.modules.demo.train.service.ITabModelTryService;
import org.jeecg.modules.demo.train.service.impl.TabModelTryServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.io.File;
import java.util.List;

/**
 * @Description: 训练图片
 * @Author: WGAI
 * @Date:   2024-12-17
 * @Version: V1.0
 */
@Service
public class TabEasyPicServiceImpl extends ServiceImpl<TabEasyPicMapper, TabEasyPic> implements ITabEasyPicService {

    @Autowired
    ITabModelTryService iTabModelTryService;
    @Value("${jeecg.path.upload}")
    String uploadPath;
    @Override
    public void sumPic(String id) {

        int numberPic=0;
        int markNumber=0;
        double picMb=0;
        List<TabEasyPic> tabEasyPicList=this.list(new LambdaQueryWrapper<TabEasyPic>().eq(TabEasyPic::getModelId,id));
        numberPic=tabEasyPicList.size();
        for (TabEasyPic tab:tabEasyPicList) {
            if (tab.getMarkType().equals("Y")) {//标注了
                markNumber++;
            }

            File fileimg = new File(uploadPath + File.separator + tab.getPicUrl());
            if (fileimg.exists() && fileimg.isFile()) {
                long fileSizeInBytes = fileimg.length(); // 获取文件大小（单位：字节）
                picMb += fileSizeInBytes / (1024.0 * 1024.0); // 转换为MB
            }
        }
        TabModelTry modelTry=iTabModelTryService.getById(id);
        modelTry.setPicNumber(numberPic+"");
        modelTry.setMakeNumber(markNumber+"");
        modelTry.setFileSize(picMb);
        modelTry.setRunState(0);
        iTabModelTryService.updateById(modelTry);
    }
}
