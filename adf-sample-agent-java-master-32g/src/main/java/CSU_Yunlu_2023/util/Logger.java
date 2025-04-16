package CSU_Yunlu_2023.util;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

public class Logger {
    private static final String LOG_DIR = "logs";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static final SimpleDateFormat FILE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static final ConcurrentHashMap<String, Logger> instances = new ConcurrentHashMap<>();
    
    private final String className;
    private PrintWriter logWriter;
    private final String logFile;
    private boolean debugEnabled = true;
    
    private Logger(String className) {
        this.className = className;
        this.logFile = initializeLogFile();
    }
    
    public static Logger getLogger(Class<?> clazz) {
        return instances.computeIfAbsent(clazz.getName(), Logger::new);
    }
    
    private String initializeLogFile() {
        try {
            // 创建日志目录
            File dir = new File(LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            // 创建日志文件
            String fileName = LOG_DIR + "/" + FILE_DATE_FORMAT.format(new Date()) + ".log";
            logWriter = new PrintWriter(new FileWriter(fileName, true));
            return fileName;
        } catch (IOException e) {
            System.err.println("无法创建日志文件: " + e.getMessage());
            return null;
        }
    }
    
    public void debug(String message) {
        if (debugEnabled) {
            log("DEBUG", message);
        }
    }
    
    public void info(String message) {
        log("INFO", message);
    }
    
    public void warn(String message) {
        log("WARN", message);
    }
    
    public void error(String message) {
        log("ERROR", message);
    }
    
    public void error(String message, Throwable e) {
        log("ERROR", message);
        if (e != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            log("ERROR", sw.toString());
        }
    }
    
    private void log(String level, String message) {
        String timestamp = DATE_FORMAT.format(new Date());
        String logMessage = String.format("[%s] [%s] [%s] %s", 
            timestamp, level, className, message);
        
        // 输出到控制台
        System.out.println(logMessage);
        
        // 写入日志文件
        if (logWriter != null) {
            logWriter.println(logMessage);
            logWriter.flush();
        }
    }
    
    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
    }
    
    public void close() {
        if (logWriter != null) {
            logWriter.close();
        }
    }
    
    // 性能监控日志
    public void logPerformance(String metric, double value) {
        String timestamp = DATE_FORMAT.format(new Date());
        String logMessage = String.format("[%s] [PERFORMANCE] [%s] %s: %.2f", 
            timestamp, className, metric, value);
        
        System.out.println(logMessage);
        if (logWriter != null) {
            logWriter.println(logMessage);
            logWriter.flush();
        }
    }
    
    // 系统状态日志
    public void logSystemStatus(String status, String details) {
        String timestamp = DATE_FORMAT.format(new Date());
        String logMessage = String.format("[%s] [SYSTEM] [%s] %s: %s", 
            timestamp, className, status, details);
        
        System.out.println(logMessage);
        if (logWriter != null) {
            logWriter.println(logMessage);
            logWriter.flush();
        }
    }
} 