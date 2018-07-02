package com.falmarri;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.ContextDataInjector;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.ManagerFactory;
import org.apache.logging.log4j.core.config.AppenderControl;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.impl.ContextDataInjectorFactory;
import org.apache.logging.log4j.core.util.Booleans;
import org.apache.logging.log4j.util.ReadOnlyStringMap;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

/**
 * This Appender allows the logging event to be manipulated before it is processed by other Appenders.
 */
@Plugin(name = "Accumulate", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public final class AccumulatingAppender extends AbstractAppender {

    private final ContextDataInjector injector = ContextDataInjectorFactory.createInjector();

    private final Filter filter;
    private final Level accumulateLevel;
    private final Level triggerLevel;
    private final Configuration config;

    private final ConcurrentMap<String, AppenderControl> appenders = new ConcurrentHashMap<>();
    private final AppenderRef[] appenderRefs;
    private final CacheManager manager;

    static final class FactoryData {
        protected final int maxAccumulation;
        protected final int maxSize;
        protected final String groupBy;
        protected final Duration duration;

        FactoryData(int maxAccumulation, int maxSize, String groupBy, Duration duration) {
            this.maxAccumulation = maxAccumulation;
            this.maxSize = maxSize;
            this.groupBy = groupBy;
            this.duration = duration;
        }
    }


    /**
     * Factory to create the Appender.
     */
    private static class CacheManagerFactory implements ManagerFactory<CacheManager, FactoryData> {

        /**
         * Create an OutputStreamManager.
         *
         * @param name The name of the entity to manage.
         * @param data The data required to create the entity.
         * @return The OutputStreamManager
         */
        @Override
        public CacheManager createManager(final String name, final FactoryData data) {

            return new CacheManager(name, data.groupBy.split(","), CacheBuilder.newBuilder()
                .maximumSize(data.maxSize)
                .expireAfterAccess(data.duration)
                .build(
                    new CacheLoader<String, Queue<LogEvent>>() {
                        public Queue<LogEvent> load(String key) throws Exception {
                            return new CircularFifoQueue<LogEvent>(data.maxAccumulation);
                        }
                    }));
        }
    }

    private AccumulatingAppender(
        final String name,
        Filter filter,
        boolean ignoreExceptions,
        Level accumulateLevel,
        Level triggerLevel,
        Configuration config,
        AppenderRef[] appenderRefs,
        final CacheManager manager
    ) {
        super(name, null, null);
        this.filter = filter;
        this.accumulateLevel = accumulateLevel;
        this.triggerLevel = triggerLevel;
        this.config = config;
        this.appenderRefs = appenderRefs;
//        super(name, filter, null, ignoreExceptions);
//        this.maxSize = maxSize;
//        this.accumulateLevel = accumulateLevel;
//        this.triggerLevel = triggerLevel;
//        this.maxAccumulation = maxAccumulation;
//        this.config = config;
//        this.groupBy = groupBy;
//        this.appenderRefs = appenderRefs;

        this.manager = manager;
    }

    @Override
    public void start() {
        for (final AppenderRef ref : appenderRefs) {
            final String name = ref.getRef();
            final Appender appender = config.getAppender(name);
            if (appender != null) {
                final Filter filter = appender instanceof AbstractAppender ?
                    ((AbstractAppender) appender).getFilter() : null;
                appenders.put(name, new AppenderControl(appender, ref.getLevel(), filter));
            } else {
                LOGGER.error("Appender " + ref + " cannot be located. Reference ignored");
            }
        }
        super.start();
    }

    /**
     * Modifies the event and pass to the subordinate Appenders.
     * @param event The LogEvent.
     */
    @Override
    public void append(LogEvent event) {

        ReadOnlyStringMap context = injector.rawContextData();
        StringBuilder groupByString = new StringBuilder();
        for(String group : manager.getGroupBy()){
            Object value = context.getValue(group);
            if (value != null) {
                groupByString.append(value.toString());
            }
        }

        String cacheKey = groupByString.toString();

        // If none of the group by keys show up in the thread context map, just push the event downstream without
        // interacting with any of the buffers
        if (!cacheKey.isEmpty()) {
            if (!event.getLevel().isMoreSpecificThan(triggerLevel)) {
                if (event.getLevel().isMoreSpecificThan(accumulateLevel)) {

                    Queue<LogEvent> eventQueue;
                    try {
                        eventQueue = manager.getCache().get(cacheKey);
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                    eventQueue.add(event.toImmutable());
                    return;
                }

            } else {
                // Dump the buffer

                Queue<LogEvent> buffer = manager.getCache().getIfPresent(cacheKey);
                manager.getCache().invalidate(cacheKey);
                // Walk the queue first so that each appender gets events at the same time, rather than
                // appender1 getting all buffered events, then appender2 getting all buffered events, etc
                if (buffer != null) {
                    for (final LogEvent bufferedEvent : buffer) {
                        for (final AppenderControl control : appenders.values()) {
                            control.callAppender(bufferedEvent);
                        }
                    }
                }
            }
        }

        // Now add the new event that caused the buffer dump, or catch the fall through in the case that
        // This event is lower than the accumulate level. We still want to send lower levels through to the
        // underlying appenders in case they have lower levels enabled
        for (final AppenderControl control : appenders.values()) {
            control.callAppender(event);
        }
    }

    /**
     */
    @PluginFactory
    public static AccumulatingAppender createAppender(
        @PluginAttribute("name") final String name,
        @PluginAttribute("ignoreExceptions") final String ignore,
        @PluginElement("AppenderRef") final AppenderRef[] appenderRefs,
        @PluginConfiguration final Configuration config,
        @PluginAttribute("groupby") final String groupBy,
        @PluginAttribute("maxsize") final int maxSize,
        @PluginAttribute("duration") final long duration,
        @PluginAttribute("accumulateLevel") final Level accumulateLevel,
        @PluginAttribute("triggerLevel") final Level triggerLevel,
        @PluginAttribute("maxAccumulation") final int maxAccumulation,
        @PluginElement("Filter") final Filter filter) {

        final boolean ignoreExceptions = Booleans.parseBoolean(ignore, true);
        if (name == null) {
            LOGGER.error("No name provided for RewriteAppender");
            return null;
        }
        if (appenderRefs == null) {
            LOGGER.error("No appender references defined for RewriteAppender");
            return null;
        }

        String cacheName = "Accumulate{name=" + name + ",groupby=" + groupBy + ",maxsize=" + maxSize + ",duration=" + duration + ",maxAccumulation=" + maxAccumulation + "}";

        CacheManager manager = CacheManager.getManager(
            cacheName,
            new CacheManagerFactory(),
            new FactoryData(
                maxAccumulation == 0 ? 100 : maxAccumulation,
                maxSize == 0 ? 1000 : maxSize,
                groupBy,
                duration == 0 ? Duration.ofMinutes(5) : Duration.ofMillis(duration)
            )
        );

        return new AccumulatingAppender(
            name,
            filter,
            ignoreExceptions,
            accumulateLevel == null ? Level.DEBUG : accumulateLevel,
            triggerLevel == null ? Level.ERROR : triggerLevel,
            config,
            appenderRefs,
            manager
        );
    }
}
