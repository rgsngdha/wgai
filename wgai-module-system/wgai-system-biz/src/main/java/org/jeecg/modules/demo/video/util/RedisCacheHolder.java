package org.jeecg.modules.demo.video.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author wggg
 * @date 2025/7/29 19:11
 */
public class RedisCacheHolder {

    // 本地缓存
    private static final Map<String, Boolean> cacheMap = new ConcurrentHashMap<>();

    public static void put(String key, Boolean value) {
        cacheMap.put(key, value);
    }

    public static Boolean get(String key) {
        return cacheMap.get(key);
    }

    public static Map<String, Boolean> getAll() {
        return cacheMap;
    }

    public static void clear() {
        cacheMap.clear();
    }

}
