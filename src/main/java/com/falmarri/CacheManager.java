package com.falmarri;

import com.google.common.cache.LoadingCache;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractManager;

import java.util.Queue;

public class CacheManager extends AbstractManager {
    private final String[] groupBy;
    private final LoadingCache<String, Queue<LogEvent>> cache;

    protected CacheManager(final String name, String[] groupBy, LoadingCache<String, Queue<LogEvent>> cache) {
        super(null, name);
        this.groupBy = groupBy;
        this.cache = cache;
    }

    public LoadingCache<String, Queue<LogEvent>> getCache() {
        return cache;
    }

    public String[] getGroupBy() {
        return groupBy;
    }
}
