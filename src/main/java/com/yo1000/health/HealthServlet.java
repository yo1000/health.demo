package com.yo1000.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * @author yo1000
 */
public class HealthServlet extends HttpServlet {
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthServlet.class);

    private static final String ALIVE_SQL = "SELECT 1";
    private static final String ALIVE_FILE = "/WEB-INF/.health";
    private static final String BEAN_NAME_DATASOURCE_MASTER = "dataSourceMaster";
    private static final String BEAN_NAME_DATASOURCE_SLAVE = "dataSourceSlave";

    private static final String HTML_HEAD = "<head><meta charset=\"utf-8\"/></head>";
    private static final Map<Integer, String> HTMLS = new HashMap<Integer, String>() {{
        put(200, makeHtml(200, "OK"));
        put(500, makeHtml(500, "Internal Server Error"));
        put(503, makeHtml(503, "Service Unavailable"));
    }};

    private static long safeHeapMinSize = 1000000000L; // default 1GB
    private static long jspWarmUpWaitTimeInMillis = 120000L; // default 2min
    private static long unexpectedDeadTimeInMillis = 900000L; // default 15min
    private static long requestTimeoutInMillis = 10000L; // default 10sec
    private static long requestTimeoutCriticallyInMillis = 300000L; // default 5min
    private static long requestTimeoutConditionallyTermInMillis = 900000L; // default 15min
    private static int requestTimeoutClearThreshold = 10; // default 10

    private static ApplicationContext applicationContext = null;
    private static DataSource dataSourceMaster = null;
    private static DataSource dataSourceSlave = null;

    private static boolean warmedUp = false;
    private static long timeoutStart = 0L;
    private static long clearCount = 0L;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        String safeHeapMinSizeOrElse = config.getInitParameter("com.yo1000.health.safeHeapMinSize");
        if (safeHeapMinSizeOrElse != null && safeHeapMinSizeOrElse.matches("^[1-9][0-9]*$")) {
            safeHeapMinSize = Long.parseLong(safeHeapMinSizeOrElse);
        }

        String jspWarmUpWaitTimeInMillisOrElse = config.getInitParameter("com.yo1000.health.jspWarmUpWaitTimeInMillis");
        if (jspWarmUpWaitTimeInMillisOrElse != null && jspWarmUpWaitTimeInMillisOrElse.matches("^[1-9][0-9]*$")) {
            jspWarmUpWaitTimeInMillis = Long.parseLong(jspWarmUpWaitTimeInMillisOrElse);
        }

        String unexpectedDeadTimeInMillisOrElse = config.getInitParameter("com.yo1000.health.unexpectedDeadTimeInMillis");
        if (unexpectedDeadTimeInMillisOrElse != null && unexpectedDeadTimeInMillisOrElse.matches("^[1-9][0-9]*$")) {
            unexpectedDeadTimeInMillis = Long.parseLong(unexpectedDeadTimeInMillisOrElse);
        }

        String requestTimeOutInMillisOrElse = config.getInitParameter("com.yo1000.health.requestTimeoutInMillis");
        if (requestTimeOutInMillisOrElse != null && requestTimeOutInMillisOrElse.matches("^[1-9][0-9]*$")) {
            requestTimeoutInMillis = Long.parseLong(requestTimeOutInMillisOrElse);
        }

        String requestTimeoutCriticallyInMillisOrElse = config.getInitParameter("com.yo1000.health.requestTimeoutCriticallyInMillis");
        if (requestTimeoutCriticallyInMillisOrElse != null && requestTimeoutCriticallyInMillisOrElse.matches("^[1-9][0-9]*$")) {
            requestTimeoutCriticallyInMillis = Long.parseLong(requestTimeoutCriticallyInMillisOrElse);
        }

        String requestTimeoutConditionallyTermInMillisOrElse = config.getInitParameter("com.yo1000.health.requestTimeoutConditionallyTermInMillis");
        if (requestTimeoutConditionallyTermInMillisOrElse != null && requestTimeoutConditionallyTermInMillisOrElse.matches("^[1-9][0-9]*$")) {
            requestTimeoutConditionallyTermInMillis = Long.parseLong(requestTimeoutConditionallyTermInMillisOrElse);
        }

        String requestTimeoutClearThresholdOrElse = config.getInitParameter("com.yo1000.health.requestTimeoutClearThreshold");
        if (requestTimeoutClearThresholdOrElse != null && requestTimeoutClearThresholdOrElse.matches("^[1-9][0-9]*$")) {
            requestTimeoutClearThreshold = Integer.parseInt(requestTimeoutClearThresholdOrElse);
        }

        LOGGER.debug("safeHeapMinSize: {}", safeHeapMinSize);
        LOGGER.debug("jspWarmUpWaitTimeInMillis: {}", jspWarmUpWaitTimeInMillis);
        LOGGER.debug("unexpectedDeadTimeInMillis: {}", unexpectedDeadTimeInMillis);
        LOGGER.debug("requestTimeoutInMillis: {}", requestTimeoutInMillis);
        LOGGER.debug("requestTimeoutCriticallyInMillis: {}", requestTimeoutCriticallyInMillis);
        LOGGER.debug("requestTimeoutConditionallyTermInMillis: {}", requestTimeoutConditionallyTermInMillis);
        LOGGER.debug("requestTimeoutClearThreshold: {}", requestTimeoutClearThreshold);
    }

    private static int resolveStatusCode(int currentStatus, int newStatus) {
        if (currentStatus == 200) {
            return newStatus;
        } else {
            return currentStatus;
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html; charset=UTF-8");

        long start = System.currentTimeMillis();
        int statusCode = 200;

        if (isSuspending()) {
            statusCode = resolveStatusCode(statusCode, 503);
        }

        if (!warmedUp()) {
            statusCode = resolveStatusCode(statusCode, 503);
        }

        if (!aliveHeap()) {
            statusCode = resolveStatusCode(statusCode, 503);
        }

        if (!aliveDataSourceMaster()) {
            statusCode = resolveStatusCode(statusCode, 500);
        }

        if (!aliveDataSourceSlave()) {
            statusCode = resolveStatusCode(statusCode, 500);
        }

        long elapsed = System.currentTimeMillis() - start;

        if (timeoutCritically(elapsed)) {
            LOGGER.error("Application is dead. I'll die. `exit 9000`");
            System.exit(9000);
        }

        if (timeout(elapsed)) {
            statusCode = resolveStatusCode(statusCode, 503);
        }

        if (timeoutConditionally(elapsed)) {
            LOGGER.error("Application is dead. I'll die. `exit 8000`");
            System.exit(8000);
        }

        resp.setStatus(statusCode);
        try (PrintWriter writer = resp.getWriter()) {
            writer.write(HTMLS.get(statusCode));
        }
    }

    private boolean timeoutCritically(long elapsed) {
        if (elapsed < requestTimeoutCriticallyInMillis) {
            return false;
        }

        LOGGER.warn("Status: critical timeout. elapsed: {}ms", elapsed);
        return true;
    }

    private boolean timeout(long elapsed) {
        if (elapsed < requestTimeoutInMillis) {
            clearCount++;

            if (clearCount >= requestTimeoutClearThreshold) {
                clearCount = 0L;
                timeoutStart = 0L;
            }

            return false;
        }

        LOGGER.warn("Status: timeout. elapsed: {}ms", elapsed);
        return true;
    }

    private boolean timeoutConditionally(long elapsed) {
        long now = System.currentTimeMillis();
        if (timeoutStart <= 0L) {
            timeoutStart = now;
        }

        long term = now - timeoutStart;
        if (term < requestTimeoutConditionallyTermInMillis) {
            return false;
        }

        LOGGER.warn("Status: conditional timeout. elapsed: {}ms, term: {}ms", elapsed, term);
        return true;
    }

    private boolean isSuspending() {
        try {
            URL healthFile = getServletContext().getResource(ALIVE_FILE);
            if (healthFile == null) {
                URL rootFile = getServletContext().getResource("/");
                LOGGER.debug(rootFile.toString());
                LOGGER.info("Status: suspending");
                return true;
            } else {
                return false;
            }
        } catch (MalformedURLException e) {
            LOGGER.warn("Status: suspending", e);
            return true;
        }
    }

    private static boolean warmedUp() {
        if (warmedUp) return true;

        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        if (uptime >= jspWarmUpWaitTimeInMillis) {
            warmedUp = true;
        }

        if (!warmedUp) {
            LOGGER.warn("Status: not yet warmed up. {}ms remaining time.",
                    jspWarmUpWaitTimeInMillis - uptime);
        }

        return warmedUp;
    }

    private static boolean aliveHeap() {
        MemoryMXBean mbean = ManagementFactory.getMemoryMXBean();
        MemoryUsage memoryUsage = mbean.getHeapMemoryUsage();

        long max = memoryUsage.getMax();
        long committed = memoryUsage.getCommitted();
        long used = memoryUsage.getUsed();
        long available = (max > 0L ? max : committed) - used;

        if (available > safeHeapMinSize) {
            return true;
        }

        LOGGER.warn("Status: heap memory is over warning threshold. " +
                "max: {}, committed: {}, used: {}, available: {}",
                max, committed, used, available);
        return false;
    }

    private void initApplicationContext() {
        if (applicationContext == null) {
            applicationContext = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
        }
    }

    private DataSource getDataSourceBy(DataSource cached, String beanName) {
        initApplicationContext();
        if (cached != null) {
            return cached;
        }
        if (!applicationContext.containsBean(beanName)) {
            return null;
        }
        return applicationContext.getBean(beanName, DataSource.class);
    }

    private DataSource getDataSourceMaster() {
        dataSourceMaster = getDataSourceBy(dataSourceMaster, BEAN_NAME_DATASOURCE_MASTER);
        return dataSourceMaster;
    }

    private DataSource getDataSourceSlave() {
        dataSourceSlave = getDataSourceBy(dataSourceSlave, BEAN_NAME_DATASOURCE_SLAVE);
        return dataSourceSlave;
    }

    private boolean aliveDataSourceMaster() {
        DataSource dataSource = getDataSourceMaster();
        if (dataSource == null) {
            LOGGER.warn("Status: failed connect to master1, dataSource is null");
            return false;
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ALIVE_SQL);
            return true;
        } catch (SQLException e) {
            LOGGER.warn("Status: failed connect to master1", e);
            return false;
        }
    }

    private boolean aliveDataSourceSlave() {
        DataSource dataSource = getDataSourceSlave();
        if (dataSource == null) {
            LOGGER.warn("Status: failed connect to slave, dataSource is null");
            return false;
        }

        try (Connection connection = dataSourceSlave.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ALIVE_SQL);
            return true;
        } catch (SQLException e) {
            LOGGER.warn("Status: failed connect to slave", e);
            return false;
        }
    }

    private static String makeHtml(int status, String message) {
        if (status == 200) {
            return "<!doctype html><html>" + HTML_HEAD +
                    "<body><div id=\"status\"><h1>" +
                    status + "</h1></div><div id=\"message\"><p>" +
                    message + "</p></div></body></html>";
        } else {
            return "<!doctype html><html>" + HTML_HEAD +
                    "<body><div id=\"status\"><h1>" +
                    status + "</h1></div><div id=\"message\"><p>" +
                    message + "</p></div></body></html>";
        }
    }
}
