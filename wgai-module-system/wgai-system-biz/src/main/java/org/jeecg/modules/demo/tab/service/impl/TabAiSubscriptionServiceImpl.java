package org.jeecg.modules.demo.tab.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.audio.mapper.TabAudioDeviceMapper;
import org.jeecg.modules.demo.tab.entity.PushInfo;
import org.jeecg.modules.demo.tab.entity.TabAiSubscription;
import org.jeecg.modules.demo.tab.mapper.TabAiSubscriptionMapper;
import org.jeecg.modules.demo.tab.service.ITabAiSubscriptionService;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.mapper.TabAiModelMapper;
import org.jeecg.modules.tab.service.impl.TabAiModelServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @Description: Ai事件订阅
 * @Author: WGAI
 * @Date:   2024-04-08
 * @Version: V1.0
 */
@Service
public class TabAiSubscriptionServiceImpl extends ServiceImpl<TabAiSubscriptionMapper, TabAiSubscription> implements ITabAiSubscriptionService {

    @Autowired
    RedisTemplate redisTemplate;

    @Autowired
    TabAiModelServiceImpl tabAiModelServiceImpl;

    @Autowired
    TabAudioDeviceMapper tabAudioDeviceMapper;
    @Override
    public void insertRedisSubscription(TabAiSubscription aiSubscript) {
 //       List<TabAiSubscription> listSubscription=this.list();
        List<PushInfo> PushList=new ArrayList<>();
   //     for (TabAiSubscription aiSubscript:listSubscription) {
        //    if(aiSubscript.getPushStatic()==0){
                List<String> stringList= Arrays.asList(aiSubscript.getEventTypes().split(","));
                List<TabAiModel>  tabAiModels=tabAiModelServiceImpl.listByIds(stringList);
                PushInfo pushInfo=new PushInfo();
                pushInfo.setName(aiSubscript.getName());
                pushInfo.setPushId(aiSubscript.getId());
                pushInfo.setPushUrl(aiSubscript.getEventUrl());
                pushInfo.setVideoURL(aiSubscript.getRemake());
                pushInfo.setTabAiModelList(tabAiModels);
                pushInfo.setTime(Integer.parseInt(aiSubscript.getEventNumber()));
                pushInfo.setIndexCode(aiSubscript.getIndexCode());
                pushInfo.setPushStatic(aiSubscript.getPushStatic());
                if(aiSubscript.getAudioStatic()!=null&&aiSubscript.getAudioStatic()==0){
                    pushInfo.setAudioStatic(aiSubscript.getAudioStatic());
                    pushInfo.setAudioId(tabAudioDeviceMapper.selectById(aiSubscript.getAudioId()));
                }else{
                    pushInfo.setAudioStatic(1);
                }

                if(StringUtils.isNotEmpty(aiSubscript.getIsBegin())&&aiSubscript.getIsBegin().equals("0")){
                    pushInfo.setIsBegin(0);
                    pushInfo.setBeginName(aiSubscript.getBeginName());
                    pushInfo.setBeginEventTypes(tabAiModelServiceImpl.getById(aiSubscript.getBeginEventTypes()));
                }else{
                    pushInfo.setIsBegin(1);
                }
                pushInfo.setPyType(aiSubscript.getPyType());
                PushList.add(pushInfo);
        //    }
    //    }
        redisTemplate.opsForValue().set("sendPush",PushList,365, TimeUnit.DAYS);
    }
}
