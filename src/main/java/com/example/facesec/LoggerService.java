package com.example.facesec;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LoggerService {

    private PrintWriter writer;
    private ScheduledExecutorService flusher;
    private final String basePath;
    private int lastLogDay = -1;

    // FaceSecApp에서 이 값을 제어할 수 있습니다.
    public boolean isDebugMode = true;

    public LoggerService(String basePath) {
        this.basePath = basePath;
    }

    public void init() {
        // 5초마다 flush를 실행할 스케줄러를 생성합니다.
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Logger-Flusher");
            t.setDaemon(true);
            return t;
        });
        // 5초 후에 시작하여 5초 간격으로 반복 실행합니다.
        flusher.scheduleAtFixedRate(this::flush, 5, 5, TimeUnit.SECONDS);
    }

    private void openWriter() {
        try {
            Date now = new Date();
            Calendar cal = Calendar.getInstance();
            cal.setTime(now);
            int day = cal.get(Calendar.DAY_OF_MONTH);

            File dir = new File(basePath, "logs");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File file = new File(dir, "facelog_" + day + ".log");

            boolean appendMode = true;
            if (file.exists()) {
                // 파일이 있지만 다른 달의 파일인 경우 덮어쓰기 모드로 전환합니다.
                if (!isSameDate(file.lastModified(), now.getTime())) {
                    appendMode = false;
                }
            }
            
            // 기존 writer가 있으면 닫아줍니다.
            if (writer != null) {
                writer.close();
            }

            // 새 writer를 생성합니다. autoFlush는 true로 설정하여 안정성을 높입니다.
            this.writer = new PrintWriter(new FileWriter(file, appendMode), true); 
            this.lastLogDay = day;

        } catch (IOException e) {
            System.err.println("[LoggerService] 로그 파일을 열지 못했습니다: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public synchronized void log(String type, String msg) {
        if (!isDebugMode && !type.equals("FATAL")) {
            return;
        }
        // 특정 메시지는 로그에서 제외합니다.
        if (msg.contains("raonraon") || msg.contains("raonnice")) {
            return;
        }

        try {
            // 날짜가 변경되었는지 확인하여 새 로그 파일을 시작합니다.
            Date now = new Date();
            Calendar cal = Calendar.getInstance();
            cal.setTime(now);
            int currentDay = cal.get(Calendar.DAY_OF_MONTH);

            if (writer == null || currentDay != lastLogDay) {
                openWriter();
            }
            
            if (writer != null) {
                String timeStr = new SimpleDateFormat("HH:mm:ss.SSS").format(now);
                writer.println(String.format("[%s] [%s] %s", timeStr, type, msg));
            }
        } catch (Exception e) {
            System.err.println("[LoggerService] 로그 작성에 실패했습니다: " + e.getMessage());
        }
    }

    private synchronized void flush() {
        if (writer != null) {
            writer.flush();
        }
    }

    public synchronized void close() {
        // 스케줄러를 먼저 종료합니다.
        if (flusher != null) {
            flusher.shutdown();
            try {
                // 남은 작업을 완료하기 위해 잠시 대기합니다.
                flusher.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 마지막으로 writer를 flush하고 닫습니다.
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    private boolean isSameDate(long time1, long time2) {
        Calendar c1 = Calendar.getInstance();
        Calendar c2 = Calendar.getInstance();
        c1.setTimeInMillis(time1);
        c2.setTimeInMillis(time2);
        
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
               c1.get(Calendar.MONTH) == c2.get(Calendar.MONTH) &&
               c1.get(Calendar.DAY_OF_MONTH) == c2.get(Calendar.DAY_OF_MONTH);
    }
}
