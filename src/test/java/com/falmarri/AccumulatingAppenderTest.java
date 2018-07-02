package com.falmarri;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.junit.LoggerContextRule;
import org.apache.logging.log4j.test.appender.ListAppender;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;

public class AccumulatingAppenderTest {

    private ListAppender list;

    @Before
    public void setUp() throws Exception {
        this.list = init.getListAppender("List");
    }

    @After
    public void tearDown() throws Exception {
        this.list.clear();
    }

    @ClassRule
    public static LoggerContextRule init = new LoggerContextRule("log4j-accumulator.xml");

    @Test
    public void testAppender() throws Exception {
        Logger logger = LogManager.getLogger(AccumulatingAppenderTest.class);
        ThreadContext.put("traceToken", "1");
        logger.debug("Test accumulate debug");
        List<String> list = this.list.getMessages();
        Assert.assertTrue("Incorrect number of events. Expected 0, got " + list.size(), list.size() == 0);

        logger.info("Test accumulate info");
        logger.warn("Test accumulate warn");

        list = this.list.getMessages();
        Assert.assertTrue("Incorrect number of events. Expected 0, got " + list.size(), list.size() == 0);

        logger.error("Should trigger dump");

        list = this.list.getMessages();
        Assert.assertTrue("Incorrect number of events. Expected 4, got " + list.size(), list.size() == 4);

        String[] message1 = list.get(0).trim().split(",");
        Assert.assertEquals("Test accumulate debug", message1[3]);

        String[] message2 = list.get(1).trim().split(",");
        Assert.assertEquals("Test accumulate info", message2[3]);

        String[] message3 = list.get(2).trim().split(",");
        Assert.assertEquals("Test accumulate warn", message3[3]);

        String[] message4 = list.get(3).trim().split(",");
        Assert.assertEquals("Should trigger dump", message4[3]);


    }

}
