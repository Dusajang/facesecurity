package com.example.facesec;

import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import org.bytedeco.opencv.global.opencv_videoio; // 비디오 입출력
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.W32APIOptions;

public class FaceSecApp {
	private static final long serialVersionUID = 1L;

	// [수정] User32 인터페이스 확장
    public interface User32 extends Library {

    	// ★★★ [수정됨] W32APIOptions.DEFAULT_OPTIONS 옵션 추가 (필수)
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);
        HWND FindWindow(String lpClassName, String lpWindowName);
        boolean ShowWindow(HWND hWnd, int nCmdShow);
        boolean SetForegroundWindow(HWND hWnd);
        boolean SetWindowPos(HWND hWnd, HWND hWndInsertAfter, int X, int Y, int cx, int cy, int uFlags);

        // ★★★ [추가] 키보드 입력 시뮬레이션 함수 ★★★
        void keybd_event(byte bVk, byte bScan, int dwFlags, int dwExtraInfo);
    }
    
    private static final String BASE_PATH = getBaseDirectory();

    private static String getBaseDirectory() {
        try {
            // 1. 현재 실행 중인 JAR 파일의 위치를 가져옵니다.
            File jarPath = new File(FaceSecApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String path = jarPath.getParent(); // JAR 파일이 있는 폴더
            
            // 2. 만약 경로가 이상하게 잡혀서 'model' 폴더가 안 보인다면?
            // -> 시스템의 '현재 작업 폴더(user.dir)'를 대신 사용합니다. (안전장치)
            File modelTest = new File(path, "model");
            if (!modelTest.exists()) {
                String userDir = System.getProperty("user.dir");
                if (new File(userDir, "model").exists()) {
                    return userDir;
                }
            }
            return path;
        } catch (Exception e) {
            return "."; // 실패 시 현재 폴더
        }
    }
    
    private JFrame frame;
    private static class Style {
        private static final Font FONT_HUD = new Font("Malgun Gothic", Font.BOLD, 26);
        private static final Font FONT_GUIDE = new Font("Malgun Gothic", Font.BOLD, 14);
        private static final Font FONT_BTN = new Font("Malgun Gothic", Font.BOLD, 20);
        private static final Font FONT_TITLE = new Font("Malgun Gothic", Font.BOLD, 16);
        private static final Font FONT_TIMER = new Font("Malgun Gothic", Font.BOLD, 60);
        private static final Font FONT_CHECK = new Font("Malgun Gothic", Font.BOLD, 12);
        private static final Font FONT_BIG_TEXT = new Font("Malgun Gothic", Font.BOLD, 24);
        private static final Color COLOR_HUD_BG = new Color(0, 0, 0, 160);
        private static final Color COLOR_GUIDE_YELLOW = new Color(255, 255, 0, 150);
        private static final Color COLOR_BTN_BG = new Color(255, 204, 0); // 버튼 배경색 
        // 점선 스타일 미리 생성
        private static final BasicStroke STROKE_DASHED = new BasicStroke(3.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{10.0f, 10.0f}, 0.0f);
    }

    // [추가] JNA 상수 정의
    private static final HWND HWND_TOPMOST = new HWND(new com.sun.jna.Pointer(-1)); // 무조건 최상단
    private static final int SWP_NOSIZE = 0x0001; // 크기 변경 안 함
    private static final int SWP_NOMOVE = 0x0002; // 위치 이동 안 함
    private static final int SWP_SHOWWINDOW = 0x0040; // 창 보이기
	// ===== 상수 =====
    private static final String ONNX_MODEL_PATH = BASE_PATH + File.separator + "model" + File.separator + "face_model.onnx";
    private static final String CASCADE_PATH    = BASE_PATH + File.separator + "model" + File.separator + "haarcascade_frontalface_default.xml";
    private static final File   DATASET_ROOT    = new File(BASE_PATH, "dataset");
    private static final File   INI_FILE        = new File(BASE_PATH, "face.ini");
    // [기본 설정] 보안 캡처 폴더
    private File securityCapturesDir = new File(BASE_PATH, "security_captures");
    
	// [기본 설정] INI에 값이 없으면, 실행 위치에 "security_captures" 폴더를 만듦
    private static final String APP_TITLE = "Face";
    private static final int UI_MAX_FPS = 20;  
    // [추가] 현재 등록 타겟 (예: "00105_01203")
    // null 이면 등록 대기 상태 아님
    private volatile String currentEnrollTarget = null;
    private volatile CompletableFuture<String> enrollmentFuture = null;
    private volatile CompletableFuture<String> recognitionFuture = null;
    // [추가] 보안 캡처 결과 대기용 Future
    private volatile CompletableFuture<String> securityCaptureFuture = null;
    // ===== INI 설정 변수 =====
    private int iniWinX = 100, iniWinY = 100, iniWinW = 1200, iniWinH = 800;
    private Integer iniFrameW = null, iniFrameH = null;
    private int cameraIndex = 0;
    private double iniMatchThreshold = 0.75;
    // 카메라 화면 위치 및 크기 (기본값 설정)
    private int previewX = 280;
    private int previewY = 50;
    private int previewW = 640;
    private int previewH = 480;
    // [추가] 등록 화면 오버레이용 이미지 (투명 PNG)
    private BufferedImage enrollGuideImage = null;
    // 자동 캡처 설정
    private double minFaceRatio = 0.15; // auto_capture_ratio 최소 거리
    private double maxFaceRatio = 0.20; // [신규] max_capture_ratio 최대거리
    private long autoCaptureCooldownMs = 3000L; // 재시도 쿨타임 3초
    private int autoCountdownSec = 2; // 2초 카운트다운
    // [추가] 중복 촬영 방지용 잠금 장치
    private volatile boolean isProcessing = false;
    // 자동 캡처 상태 변수
    private long lastAutoCaptureTime = 0L;
    private boolean autoCountdownActive = false;
    private long autoCountdownStartMs = 0L;
    // 보안 캡처 설정
    private boolean securityCaptureEnabled = true;
    // 보안 캡처 저장 경로 (SETUP.INI에서 읽어올 예정)
    private String securityPath = "C:\\CAM1"; // 기본값
    // ===== UI 컴포넌트 =====
    private JTabbedPane tabbedPane;
    private JPanel enrollPanel, recognizePanel, securityPanel;
    private JLabel enrollVideoLabel, recognizeVideoLabel, securityVideoLabel;
    // [추가] 현재 화면에 띄울 가이드 메시지 (volatile 필수)
    private volatile String currentGuideMessage = "시스템 준비 중...";
    private JButton btnEnrollCancel; // 등록 화면용 취소
    private JButton btnRecogCancel; // 인식 화면용 취소
    private JCheckBox chkSecurityAuto;
    // 카메라 읽기 작업
    private final ExecutorService frameReader = Executors.newSingleThreadExecutor();
    // ===== 핵심 객체 =====
    private VideoCapture cap;
    private CascadeClassifier faceDetector;
    private FaceRecognitionService faceEngine; // DNN 엔진
    private boolean cascadeLoadedOnce = false;
    private boolean modelReady = false;
    private volatile boolean isModelLoading = true;
    // [최적화 상수] 리사이즈 및 감지용 Size 객체 미리 생성 (메모리 절약)
    private static final Size SIZE_320_180 = new Size(320, 180);
    private static final Size SIZE_20_20 = new Size(20, 20);
    private static final Size SIZE_ZERO = new Size();
    private final Mat detectGrayFrame = new Mat(); 
    private final RectVector detectSmallFaces = new RectVector();
    private final Rect cachedRoiRect = new Rect();
    // [최적화] 매번 new 하지 않기 위한 재사용 객체들
    private final Size cachedResizeSize = new Size(); // 리사이즈용 치수 객체
    private final RectVector emptyFaces = new RectVector(); // 빈 얼굴 목록 (재사용)
    // [최적화] UI 리소스 상수화 (매번 new 하지 않기 위함) 

    // ===== 상태 변수 =====
    private volatile boolean running = false;
    private Thread cameraThread;
    private volatile boolean securityCaptureRequested = false;
    private volatile Mat lastFrameForCapture = null;
    // 디버그 모드 상태 (기본값: true - 켜짐)
    private static LoggerService logger;
    // [추가] 사용자가 의도한 종료인지 체크하는 깃발
    private static boolean isExpectedExit = false;
    // [추가] 삭제 작업 중인지 확인하는 깃발
    private volatile boolean isDeleting = false;

    // 인식 제어 변수
    private long lastRecognizeTime = 0;
    private String lastIdentifiedName = "";
    // [추가] 인식 모드 시작 시간 기록용
    private long recognitionStartMs = 0;
    // [신규] 등록 모드 시작 시간 기록용 ★★★
    private long enrollmentStartMs = 0;
    // [추가] 연속 인식 검증용 변수
    private int consecutiveSuccessCount = 0; // 연속 성공 횟수
    private String lastCandidateName = ""; // 직전에 인식된 이름 후보
    // [추가] 타이머 UI 및 제어 변수
    private JLabel enrollTimerLabel; // 등록 화면용 타이머
    private JLabel recognizeTimerLabel; // 인식 화면용 타이머
    // [비동기 처리] 얼굴 인식을 담당할 별도 작업자 (스레드 1개)
    private final ExecutorService detectorThread = Executors.newSingleThreadExecutor();  
    // [비동기 처리] 현재 인식이 진행 중인지 확인하는 깃발
    private final java.util.concurrent.atomic.AtomicBoolean isDetectingNow = new java.util.concurrent.atomic.AtomicBoolean(false);    
    // [비동기 처리] 감지된 얼굴 좌표를 저장하는 공유 변수 (여러 스레드가 접근하므로 volatile 필수)
    private volatile List<Rectangle> currentDetectedFaces = java.util.Collections.emptyList();
    // ★★★ [신규] 인식용(Feature Extraction) 스레드 추가 ★★★
    // 감지와 별개로, 무거운 수학 연산을 담당합니다.
    private final ExecutorService recognizerThread = Executors.newSingleThreadExecutor();
    // 현재 인식이 진행 중인지 체크하는 깃발 (중복 요청 방지)
    private final java.util.concurrent.atomic.AtomicBoolean isRecognizingNow = new java.util.concurrent.atomic.AtomicBoolean(false);
    // [타이머] 독립적으로 돌아가는 UI 타이머
    private javax.swing.Timer uiCountdownTimer = null;
    private int countdownSecondsLeft = 0;

    public FaceSecApp() {
    	frame = new JFrame(APP_TITLE);
    	
    	// 생성자 시작 시간
        long startCons = System.currentTimeMillis();

        // 1. 로그 서비스 초기화 (가장 먼저)
        logger = new LoggerService(BASE_PATH);
        logger.init();
        
        // 2. 설정 로드
        loadDebugConfig();
        loadSavePath();
        loadConfigFromIni(); 

        // 3. 로그 시작
        logger.log("SYS", "============================================");
        logger.log("SYS", "    PROGRAM STARTED (Fast Text-Init Mode) ");
        logger.log("SYS", "============================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // 1. 이미 정상 종료 플래그가 켜져있다면? (X버튼이나 TCP로 끈 경우)
            // -> 이미 앞에서 로그를 남겼으니 패스하거나 간단히 기록
            if (isExpectedExit) {
                logger.log("SYS", "============================================");
                logger.log("SYS", "    PROGRAM TERMINATED (Normal Exit)        ");
                logger.log("SYS", "============================================");
            } 
            // 2. 정상 종료 플래그가 없다? -> 윈도우 종료 or 작업관리자 Kill
            else {
                logger.log("FATAL", "============================================");
                logger.log("FATAL", " [경고] 윈도우 시스템 종료 또는 강제 종료 감지");
                logger.log("FATAL", " (Windows Shutdown or Task Manager Kill)");
                logger.log("FATAL", "============================================");
            }

            // 자원 정리
            try {
                if (logger != null) {
                    logger.close();
                }
                if (running) {
                    stopCamera();
                }
                SimpleTcpServer.stop();
            } catch (Exception e) {
                // 종료 중에 에러나면 무시
            }
        }));
        
        // 3. GUI 설정 (즉시 실행)
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        this.iniWinW = screenSize.width;
        this.iniWinH = screenSize.height;
        this.iniWinX = 0;
        this.iniWinY = 0;

        if (this.previewW == 0) this.previewW = 480;
        if (this.previewH == 0) this.previewH = 680;
        this.previewX = (screenSize.width - this.previewW) / 2;

        // 이미지 로드
        try {
            File imgFile = new File(BASE_PATH, "images/face_guide.png");
            if (imgFile.exists()) enrollGuideImage = ImageIO.read(imgFile);
            
            String iconPath = BASE_PATH + File.separator + "images" + File.separator + "icon.png";
            Image icon = Toolkit.getDefaultToolkit().getImage(iconPath);
            frame.setIconImage(icon);
        } catch (Exception e) {}
            
        // 4. UI 빌드 (즉시 화면 그리기)
        buildUI(); 
        frame.setBounds(iniWinX, iniWinY, iniWinW, iniWinH);
        
        // ★ [로그] UI 생성 완료 시간
        long uiDone = System.currentTimeMillis();
        logger.log("PERF", "UI Build Finished: " + (uiDone - startCons) + "ms from Constructor start");

        // 폴더 생성
        if (!DATASET_ROOT.exists()) DATASET_ROOT.mkdirs();
        if (!securityCapturesDir.exists()) securityCapturesDir.mkdirs();
        
        File modelDir = new File(BASE_PATH, "model");
        if (!modelDir.exists()) {
            System.err.println("경고: model 폴더가 없습니다 -> " + modelDir.getAbsolutePath());
        }

        // [3] 윈도우 리스너 ('X' 버튼 또는 Alt+F4 감지)
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                isExpectedExit = true; // ★ 사용자가 직접 껐음 표시
                logger.log("SYS", "[종료] 사용자가 X 버튼(또는 Alt+F4)으로 종료함");
                
                stopCamera();
                SimpleTcpServer.stop();
                if (logger != null) {
                    logger.close();
                }
                // 여기서 System.exit(0)을 안 해도 프레임 닫히면서 JVM이 종료 절차 밟음
            }
        });

        frame.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowLostFocus(java.awt.event.WindowEvent e) {
                if (tabbedPane != null && tabbedPane.getSelectedIndex() != 2) {
                    if (frame.getExtendedState() != JFrame.ICONIFIED && frame.isVisible()) {
                        try { forceForeground(); } catch (Exception ex) {}
                    }
                }
            }
        });

        // =========================================================================
        // ★★★ [속도 개선] AI 로딩을 백그라운드 스레드로 분리 ★★★
        // =========================================================================
        
        // 1) 일단 화면에 "준비 중"이라고 띄워놓음
        showGuideText("시스템 초기화 중... (잠시만 기다려주세요)");

        // 2. AI 로더 스레드 (비동기)
        new Thread(() -> {
            long aiStart = System.currentTimeMillis();
            logger.log("PERF", "[Thread] AI Loading Started");
            
            try {
                // [구간 1] OpenCV 엔진 초기화
                faceEngine = new FaceRecognitionService();
                faceEngine.setLogger(logger); // 로거 주입
                faceEngine.setThreshold(iniMatchThreshold);
                long engineDone = System.currentTimeMillis();
                logger.log("PERF", "[Thread] OpenCV/DLL Loaded: " + (engineDone - aiStart) + "ms");
                
                // [구간 2] ONNX 모델 로드
                if (faceEngine.loadModel(ONNX_MODEL_PATH)) {
                    long modelDone = System.currentTimeMillis();
                    logger.log("PERF", "[Thread] ONNX Model Parsed: " + (modelDone - engineDone) + "ms");
                    
                    SwingUtilities.invokeLater(() -> showGuideText("사용자 데이터 읽는 중..."));
                    
                    // [구간 3] 사용자 JPG 로드
                    int count = faceEngine.loadAllUsers(DATASET_ROOT);
                    long dbDone = System.currentTimeMillis();
                    logger.log("PERF", "[Thread] User DB Loaded (" + count + " users): " + (dbDone - modelDone) + "ms");
                    
                    SwingUtilities.invokeLater(() -> {
                        modelReady = true; 
                        showGuideText("시스템 준비 완료 (" + count + "명)");
                        // ...
                    });
                } else {
                    SwingUtilities.invokeLater(() -> showGuideText("오류: 모델 파일 없음"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            long aiEnd = System.currentTimeMillis();
            logger.log("PERF", "[Thread] Total AI Setup Time: " + (aiEnd - aiStart) + "ms");
            
        }, "AiLoaderThread").start();

        // =========================================================================

        // 카메라는 빨리 뜨니까 바로 시작 (화면이 검게 나오는 시간 단축)
        startCamera();
        
        startAlwaysOnTopWatchdog();

        // 바로 보안 탭(숨김 상태)으로 시작
        tabbedPane.setSelectedIndex(2);
        frame.setBounds(-200, -200, 1, 1);
    }

    private void buildUI() {
    	tabbedPane = new JTabbedPane();
    	// 1. 탭 버튼 영역 숨기기
    	tabbedPane.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {

    		@Override
    		protected int calculateTabAreaHeight(int tabPlacement, int horizRunCount, int maxTabHeight) {
    			return 0;
    		}
    		
    		@Override
    		protected void paintTabArea(Graphics g, int tabPlacement, int selectedIndex) {
    		}
    		
            @Override
            protected java.awt.Insets getContentBorderInsets(int tabPlacement) {
                return new java.awt.Insets(0, 0, 0, 0);
            }
            
            // ★★★ [기존] 자바가 몰래 그리는 기본 테두리 선을 '아무것도 안 그림'으로 변경 ★★★
            @Override
            protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
                // Do nothing.
            }
    		
    	});
    	// 2. 탭 변경 리스너
        tabbedPane.addChangeListener(e -> {
        	int index = tabbedPane.getSelectedIndex();
        	stopCountdown();

        	if (index == 0) {
        		//showGuideText("등록 준비 완료");
        		// ★★★ [추가] 탭 클릭으로 들어왔을 때도 시간 초기화 ★★★
        		enrollmentStartMs = System.currentTimeMillis();		
            } else if (index == 1) {
                showGuideText("인식 준비 완료"); 
            }else {
                showGuideText("보안 모드");
            }

            if (index == 2) {
            	frame.setBounds(-200, -200, 1, 1);
            } else {
            	Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            	frame.setBounds(0, 0, screen.width, screen.height);
            	frame.setExtendedState(JFrame.NORMAL);
            	frame.setVisible(true);
            	frame.toFront();
            	frame.requestFocus();
            }

        });
	
        // =================================================================
        // [공통] 취소 버튼 위치 계산 (한 번만 계산해서 재사용)
        // =================================================================
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int bottomMargin = (screen.width > screen.height) ? 100 : 50;
        // 좌표 객체 생성 (x=50, y=계산값, w=200, h=60)
        Rectangle btnRect = new Rectangle(50, screen.height - 60 - bottomMargin, 200, 60);
        
        // [수정] 배경 이미지 경로에 BASE_PATH 적용
        String bgEnroll    = BASE_PATH + File.separator + "images" + File.separator + "bg_enroll.jpg";
        String bgRecognize = BASE_PATH + File.separator + "images" + File.separator + "bg_recognize.jpg";
        String bgSecurity  = BASE_PATH + File.separator + "images" + File.separator + "bg_security.jpg";

        // -------------------------------------------------------------
        // [1] 등록 패널 (Enroll Panel)
        // -------------------------------------------------------------
        enrollPanel = new ImageBackgroundPanel(bgEnroll);
        JPanel enrollCenter = new JPanel(null);
        enrollCenter.setOpaque(false);
        enrollVideoLabel = new JLabel();
        enrollVideoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        enrollVideoLabel.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
        enrollVideoLabel.setBounds(previewX, previewY, previewW, previewH);
        enrollTimerLabel = new JLabel("");
        enrollTimerLabel.setFont(Style.FONT_TIMER);
        enrollTimerLabel.setForeground(Color.RED);
        enrollTimerLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        enrollTimerLabel.setBounds(previewX + previewW - 130, previewY + 10, 120, 70);
        JLabel guideTextLabel = new JLabel("가이드 라인에 얼굴을 맞춰주세요");
        guideTextLabel.setFont(Style.FONT_BIG_TEXT);
        guideTextLabel.setForeground(Color.WHITE);
        guideTextLabel.setHorizontalAlignment(SwingConstants.CENTER);
        guideTextLabel.setBounds(previewX, previewY + previewH + 10, previewW, 40);

        // ★ [리팩토링] 헬퍼 메서드로 버튼 생성 (코드 중복 제거)
        btnEnrollCancel = createRoundedButton("취소", e -> cancelEnrollment());
        btnEnrollCancel.setBounds(btnRect); // 미리 계산한 위치 적용
        enrollCenter.add(guideTextLabel);
        enrollCenter.add(enrollTimerLabel);
        enrollCenter.add(enrollVideoLabel);
        enrollCenter.add(btnEnrollCancel);
        enrollPanel.add(enrollCenter, BorderLayout.CENTER);
		
		// -------------------------------------------------------------
		// [2] 인식 패널 (Recognize Panel)
		// -------------------------------------------------------------
        recognizePanel = new ImageBackgroundPanel(bgRecognize);

        // ※ 만약 인식 화면도 등록 화면처럼 깔끔하게 하고 싶다면 recognizeTop 제거 고려
        JPanel recognizeTop = new JPanel();
        recognizeTop.setOpaque(false);
        JLabel titleLabel = new JLabel(" [1:N 자동 인식] ");
        titleLabel.setForeground(Color.YELLOW);
        titleLabel.setFont(Style.FONT_TITLE);
        chkSecurityAuto = new JCheckBox("자동 보안캡처", securityCaptureEnabled);
        chkSecurityAuto.setOpaque(false);
        chkSecurityAuto.setForeground(Color.WHITE);
        chkSecurityAuto.setFont(Style.FONT_CHECK);
        recognizeTop.add(titleLabel);
        recognizeTop.add(chkSecurityAuto);
        JPanel recCenter = new JPanel(null);
        recCenter.setOpaque(false);
        recognizeVideoLabel = new JLabel();
        recognizeVideoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        recognizeVideoLabel.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
        recognizeVideoLabel.setBounds(previewX, previewY, previewW, previewH);
        recognizeTimerLabel = new JLabel("");
        recognizeTimerLabel.setFont(Style.FONT_TIMER);
        recognizeTimerLabel.setForeground(Color.CYAN);
        recognizeTimerLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        recognizeTimerLabel.setBounds(previewX + previewW - 130, previewY + 10, 120, 70);

        // 하단 안내 문구 설정
        String baseText = "안면 정보를 식별 중입니다";
        JLabel recTextLabel = new JLabel(baseText + " ·");
        recTextLabel.setFont(Style.FONT_BIG_TEXT);
        recTextLabel.setForeground(Color.WHITE);
        recTextLabel.setHorizontalAlignment(SwingConstants.LEFT);
        FontMetrics fm = recTextLabel.getFontMetrics(recTextLabel.getFont());
        int textWidth = fm.stringWidth(baseText);
        int labelStartX = previewX + (previewW / 2) - (textWidth / 2);
        recTextLabel.setBounds(labelStartX, previewY + previewH + 10, textWidth + 100, 40);
        recCenter.add(recTextLabel);

        // 텍스트 애니메이션
        javax.swing.Timer textAniTimer = new javax.swing.Timer(300, e -> {
            int step = (int) (System.currentTimeMillis() / 300) % 3;

            StringBuilder sb = new StringBuilder(baseText);

            for (int i = 0; i <= step; i++) {
                sb.append(" ·");
            }

            recTextLabel.setText(sb.toString());

        });

        textAniTimer.start();
        recCenter.add(recognizeTimerLabel);
        recCenter.add(recognizeVideoLabel);

        // ★ [리팩토링] 인식 취소 버튼 생성 (중복 제거됨)
        btnRecogCancel = createRoundedButton("취소", e -> cancelRecognition());

        // ★ [리팩토링] 불필요한 rScreen, rBtnX 계산 로직 삭제 -> 공통 btnRect 사용
        btnRecogCancel.setBounds(btnRect);

        recCenter.add(btnRecogCancel);

        // 상단 바(recognizeTop) 유지 (필요 시 주석 처리하여 삭제 가능)
        // recognizePanel.add(recognizeTop, BorderLayout.NORTH); // 필요하면 주석 해제
        recognizePanel.add(recCenter, BorderLayout.CENTER);
		
		// -------------------------------------------------------------
		// [3] 보안 패널 & [4] 탭 설정 (기존과 동일)
		// -------------------------------------------------------------
        securityPanel = new ImageBackgroundPanel(bgSecurity);
        JPanel secCenter = new JPanel(null);
        secCenter.setOpaque(false);
        securityVideoLabel = new JLabel();
        securityVideoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        securityVideoLabel.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
        securityVideoLabel.setBounds(previewX, previewY, previewW, previewH);
        secCenter.add(securityVideoLabel);
        securityPanel.add(secCenter, BorderLayout.CENTER);
        tabbedPane.addTab("등록", enrollPanel);
        tabbedPane.addTab("인식(자동)", recognizePanel);
        tabbedPane.addTab("보안", securityPanel);
        tabbedPane.setSelectedIndex(2);
        
        chkSecurityAuto.addActionListener(e -> {
            securityCaptureEnabled = chkSecurityAuto.isSelected();
            //System.out.println("자동 보안캡처 설정 변경: " + securityCaptureEnabled);
        });

        frame.add(tabbedPane, BorderLayout.CENTER);

    }

    // ★★★ [신규] 버튼 생성 헬퍼 메서드 (중복 제거용) ★★★
    private JButton createRoundedButton(String text, java.awt.event.ActionListener action) {

        JButton btn = new JButton(text) {
        	
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 30, 30);
                g2.dispose();
                super.paintComponent(g);
            }

        };

        btn.setFont(Style.FONT_BTN);
        btn.setBackground(Style.COLOR_BTN_BG);
        btn.setForeground(Color.BLACK);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.addActionListener(action);

        return btn;

    }

    // [수정] 카메라 렉과 상관없이 1초마다 정확히 동작하는 Swing Timer
    private void startCountdown(int seconds, double delaySeconds, JLabel targetLabel) {
        stopCountdown(); // 기존 타이머 중지
        
        this.countdownSecondsLeft = seconds;
        targetLabel.setText(String.valueOf(seconds));
        targetLabel.setVisible(true);

        // 1초(1000ms)마다 실행되는 독립적인 타이머 생성
        uiCountdownTimer = new javax.swing.Timer(1000, e -> {
            countdownSecondsLeft--;
            
            if (countdownSecondsLeft > 0) {
                targetLabel.setText(String.valueOf(countdownSecondsLeft));
            } else {
                targetLabel.setText(""); // 0초 되면 글자 지움
                ((javax.swing.Timer)e.getSource()).stop(); // 타이머 종료
            }
        });
        
        uiCountdownTimer.setInitialDelay((int)(delaySeconds * 1000)); // 초기 딜레이 설정
        uiCountdownTimer.start();
    }

    private void stopCountdown() {
        if (uiCountdownTimer != null && uiCountdownTimer.isRunning()) {
            uiCountdownTimer.stop();
        }
        uiCountdownTimer = null;
        
        // UI 초기화
        SwingUtilities.invokeLater(() -> {
            if (enrollTimerLabel != null) enrollTimerLabel.setText("");
            if (recognizeTimerLabel != null) recognizeTimerLabel.setText("");
        });
    }

 // ===== 카메라 구동 =====
    public void startCamera() {

        if (running) {
            return;
        }
        running = true;

        // DSHOW는 로딩이 빠르므로 "준비 중" 문구 없이 바로 연결 시도
        showGuideText("카메라 연결 중...");

        cameraThread = new Thread(() -> {
            
            if (!cascadeLoadedOnce) {
                // [수정] 상수로 정의된 절대 경로(CASCADE_PATH) 사용
                faceDetector = new CascadeClassifier(CASCADE_PATH);
                
                if (faceDetector.empty()) {
                    showError("오류", "Haar Cascade 파일 로드 실패:\n" + CASCADE_PATH);
                    running = false;
                    return;
                }
                cascadeLoadedOnce = true;
            }

            // ★ [복귀] 빠릿한 DSHOW 엔진 사용
            //System.out.println("[시스템] 카메라 연결 시도 (엔진: DSHOW, 고속모드)...");
            cap = new VideoCapture(cameraIndex, opencv_videoio.CAP_DSHOW); 

            if (cap.isOpened()) {
                
                // 1. MJPG 설정을 시도는 하되, 안 먹어도 상관없음 (해상도를 낮췄기 때문)
                try {
                    int mjpg = 1196444237; // MJPG
                    cap.set(opencv_videoio.CAP_PROP_FOURCC, mjpg);
                } catch (Exception e) {}

                // 2. FPS 30 설정
                cap.set(opencv_videoio.CAP_PROP_FPS, 30);

                // 3. 해상도 설정 (INI에서 읽은 값, 없으면 800x600 기본)
                int reqW = (iniFrameW != null) ? iniFrameW : 800;
                int reqH = (iniFrameH != null) ? iniFrameH : 600;
                
                cap.set(opencv_videoio.CAP_PROP_FRAME_WIDTH, reqW);
                cap.set(opencv_videoio.CAP_PROP_FRAME_HEIGHT, reqH);
                
                showGuideText("시스템 준비 완료");
                
                // ★ 기존에는 여기에 cameraLoop(); 가 있었지만, 아래로 뺐습니다.
                
            } else {
                // ★ 기존의 running = false; 삭제됨
                showGuideText("카메라 연결 실패 (재연결 대기 중...)");
                if (logger != null) logger.log("WARN", "초기 카메라 연결 실패. 자동 재연결 모드로 진입합니다.");
            }

            // ★ [핵심] 연결 성공/실패 여부와 상관없이 무조건 메인 루프로 진입!
            // (안에서 프레임 못 읽으면 알아서 재연결 시도함)
            cameraLoop();

        }, "CameraThread");
        
        cameraThread.setDaemon(true);
        cameraThread.start();
    }

    private void cameraLoop() {
        // [1] 메모리 풀링 (Zero Allocation)
        Mat frame = new Mat();
        Mat displayFrame = new Mat(); 
        Mat analysisFrame = new Mat(); 
        
        if (lastFrameForCapture != null) lastFrameForCapture.release();
        lastFrameForCapture = new Mat(); 
        
        BufferedImage cachedImage = null; 

        // 타이밍 변수
        final long uiInterval = 1_000_000_000L / UI_MAX_FPS; 
        long lastUi = 0;
        long fpsTime = System.currentTimeMillis();
        int frameCount = 0;

        // 에러 감지 및 재연결 변수
        int consecutiveFailCount = 0;
        final int FAIL_THRESHOLD = 5; 
        
        // ★ [추가] 재연결 쿨타임 (무한 재시도 방지)
        long lastRetryTime = 0; 
        
        int missingFaceCount = 0; 
        final int MISSING_THRESHOLD = 10; // 약 0.5초 (20fps 기준) 동안은 봐줌

        //System.out.println("[시스템] 카메라 루프 시작 (자동 재연결 모드 탑재)");

        java.util.concurrent.Future<Boolean> pendingReadTask = null;
        long pendingReadStartMs = 0L;

        while (running) {
            try {
                boolean isReadSuccess = false;

                // =========================================================
                // [2] 읽기 시도 (타임아웃 적용)
                // =========================================================
                if (cap != null && cap.isOpened()) {
                    try {
                        if (pendingReadTask == null) {
                            pendingReadStartMs = System.currentTimeMillis();
                            pendingReadTask = frameReader.submit(() -> cap.read(frame) && !frame.empty());
                        }

                        if (pendingReadTask.isDone()) {
                            isReadSuccess = pendingReadTask.get();
                            pendingReadTask = null;
                        } else if (System.currentTimeMillis() - pendingReadStartMs > 300) {
                            pendingReadTask.cancel(true);
                            pendingReadTask = null;
                            isReadSuccess = false;
                        } else {
                            // 아직 timeout 전이므로 실패로 누적하지 않고 다음 루프로 넘긴다.
                            continue;
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        if (pendingReadTask != null) {
                            pendingReadTask.cancel(true);
                            pendingReadTask = null;
                        }
                        isReadSuccess = false;
                    } catch (java.util.concurrent.ExecutionException ex) {
                        if (pendingReadTask != null) {
                            pendingReadTask.cancel(true);
                            pendingReadTask = null;
                        }
                        isReadSuccess = false;
                    }
                }

                // =========================================================
                // [3] 실패 처리 & ★ 재연결(Reconnect) 로직 ★
                // =========================================================
                if (!isReadSuccess) {
                    consecutiveFailCount++;
                    
                    // 5번 이상 연속 실패 (약 1.5초) -> "끊김" 확정
                    if (consecutiveFailCount > FAIL_THRESHOLD) {
                        showGuideText("⚠ 카메라 신호 없음 (재연결 중...)");
                        
                        // 1. 에러 화면 그리기 (UI 갱신)
                        long now = System.nanoTime();
                        if (now - lastUi >= uiInterval) {
                            lastUi = now;
                            if (cachedImage == null) {
                                cachedImage = new BufferedImage(previewW, previewH, BufferedImage.TYPE_3BYTE_BGR);
                            }
                            drawCameraErrorScreen(cachedImage);
                            final BufferedImage finalImg = cachedImage;
                            int currentTab = tabbedPane.getSelectedIndex();
                            SwingUtilities.invokeLater(() -> {
                                if (currentTab == 0) updateLabelIcon(enrollVideoLabel, finalImg);
                                else if (currentTab == 1) updateLabelIcon(recognizeVideoLabel, finalImg);
                                else updateLabelIcon(securityVideoLabel, finalImg);
                            });
                        }

                        // 2. ★★★ 재연결 시도 (2초마다 수행) ★★★
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastRetryTime > 2000) {
                            lastRetryTime = currentTime;
                            //System.out.println("[시스템] 카메라 재연결 시도...");

                            try {
                                // 기존 객체 폐기 (중요: 이걸 안 하면 메모리 샙니다)
                                if (cap != null) cap.release();
                                
                                // 새 객체 생성 (DSHOW 엔진 사용)
                                cap = new VideoCapture(cameraIndex, opencv_videoio.CAP_DSHOW);
                                
                                // 설정 재적용
                                if (cap.isOpened()) {
                                    try { cap.set(opencv_videoio.CAP_PROP_FOURCC, 1196444237); } catch(Exception e){}
                                    cap.set(opencv_videoio.CAP_PROP_FPS, 30);
                                    int reqW = (iniFrameW != null) ? iniFrameW : 800;
                                    int reqH = (iniFrameH != null) ? iniFrameH : 600;
                                    cap.set(opencv_videoio.CAP_PROP_FRAME_WIDTH, reqW);
                                    cap.set(opencv_videoio.CAP_PROP_FRAME_HEIGHT, reqH);
                                    //System.out.println("[시스템] 카메라 객체 재생성 완료");
                                }
                            } catch (Exception e) {
                                System.err.println("[재연결 실패] " + e.getMessage());
                            }
                        }
                    }
                    // 실패했으면 아래 로직 건너뜀
                    continue; 
                }

                // 성공 시 카운트 초기화 (화면이 다시 나오기 시작함)
                if (consecutiveFailCount > 0) {
                    //System.out.println("[시스템] 카메라 연결 복구됨!");
                    showGuideText("시스템 정상");
                }
                consecutiveFailCount = 0;

                // =========================================================
                // [4] 이후 정상 로직 (기존과 동일)
                // =========================================================
                frameCount++;
                if (System.currentTimeMillis() - fpsTime >= 1000) {
                    long totalMB = Runtime.getRuntime().totalMemory() / (1024 * 1024);
                    long freeMB = Runtime.getRuntime().freeMemory() / (1024 * 1024);
                    long maxMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
                    long usedMB = totalMB - freeMB;
                    double usageRatio = (double) usedMB / maxMB;
                    
                    // ★ 메모리 모니터링
                    //System.out.printf("[모니터링] FPS: %d | 메모리: %d MB (%.1f%%)\n", frameCount, usedMB, usageRatio * 100);
                    //logger.log("MEM", String.format("[모니터링] FPS: %d | 메모리: %d MB (%.1f%%)", frameCount, usedMB, usageRatio * 100));
                    
                    frameCount = 0;
                    fpsTime = System.currentTimeMillis();
                }

                org.bytedeco.opencv.global.opencv_core.flip(frame, frame, 1);
                frame.copyTo(lastFrameForCapture); 

                if (securityCaptureRequested) {
                    performSecurityCapture();
                    securityCaptureRequested = false;
                }

                int tabIdx = tabbedPane.getSelectedIndex();
                int srcW = frame.cols(); 
                int srcH = frame.rows(); 

                if (tabIdx == 0 || tabIdx == 1) {
                    if (!isDetectingNow.get()) {
                        org.bytedeco.opencv.global.opencv_imgproc.resize(frame, analysisFrame, SIZE_320_180); 
                        double scaleX = (double) srcW / 320.0;
                        double scaleY = (double) srcH / 180.0;
                        isDetectingNow.set(true); 

                        detectorThread.submit(() -> {
                            try {
                                org.bytedeco.opencv.global.opencv_imgproc.cvtColor(analysisFrame, detectGrayFrame, COLOR_BGR2GRAY);
                                org.bytedeco.opencv.global.opencv_imgproc.equalizeHist(detectGrayFrame, detectGrayFrame);
                                
                                detectSmallFaces.resize(0);
                                faceDetector.detectMultiScale(detectGrayFrame, detectSmallFaces, 1.2, 3, 0, SIZE_20_20, SIZE_ZERO);
                                
                                // 이 벡터는 메인 UI가 가져가서 쓰다가, 나중에 GC가 처리하도록 둠 (이건 어쩔 수 없음)
                                java.util.ArrayList<Rectangle> originalScaleFaces = new java.util.ArrayList<>((int) detectSmallFaces.size());
                                
                                for(long i=0; i<detectSmallFaces.size(); i++) {
                                    
                                    // ★★★ [수정] try-with-resources로 감싸서 자동 해제 ★★★
                                    try (Rect r = detectSmallFaces.get(i)) {
                                        int rx = (int) (r.x() * scaleX);
                                        int ry = (int) (r.y() * scaleY);
                                        int rw = (int) (r.width() * scaleX);
                                        int rh = (int) (r.height() * scaleY);
                                        
                                        if (rx < 0) rx = 0; if (ry < 0) ry = 0;
                                        if (rx + rw > srcW) rw = srcW - rx;
                                        if (ry + rh > srcH) rh = srcH - ry;
                                        
                                        r.x(rx); r.y(ry); r.width(rw); r.height(rh);
                                        
                                        // 벡터에 값을 '복사'해 넣음
                                        originalScaleFaces.add(new Rectangle(rx, ry, rw, rh));
                                        
                                    } // 여기서 r.close()가 자동 호출되어 Native 메모리 즉시 반환
                                }
                                
                                currentDetectedFaces = originalScaleFaces;
                                
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                isDetectingNow.set(false);
                            }
                        });
                    }
                } else { 
                    currentDetectedFaces = java.util.Collections.emptyList(); 
                }

                List<Rectangle> facesToDraw = currentDetectedFaces; 
                double maxRatio = 0.0;

                if (tabIdx == 0) { // 등록 탭
                    // 타겟 체크
                    if (currentEnrollTarget != null) {
                        long elapsedEnroll = System.currentTimeMillis() - enrollmentStartMs;

                        if (elapsedEnroll < 3500) {
                            showGuideText("얼굴 등록을 준비 중입니다...");
                            missingFaceCount = 0; // 준비 중일 때는 카운터 0으로 유지
                        } else {
                            // ★ [핵심 변수] 안쪽 함수가 메시지를 처리했는지 확인
                            boolean messageHandled = false; 

                            if (facesToDraw.size() > 0) {
                                // ★ [수정 1] 얼굴이 보이면 '실종 카운터' 즉시 초기화
                                missingFaceCount = 0;

                                Rectangle r = facesToDraw.get(0);
                                int centerX = r.x + r.width / 2;
                                int centerY = r.y + r.height / 2;
                                
                                // 중앙 허용 오차 (25%로 넉넉하게 잡음)
                                int tolerance = (int)(srcW * 0.25); 
                                
                                boolean isCentered = Math.abs(centerX - frame.cols()/2) < tolerance 
                                                  && Math.abs(centerY - frame.rows()/2) < tolerance;
                                
                                if (isCentered) {
                                    double curRatio = (double) r.width / srcW;
                                    maxRatio = curRatio;
                                    
                                    // ★ 처리 중이 아닐 때 진입하고, 결과(메시지 띄웠는지)를 받음
                                    if (!isProcessing) {
                                        messageHandled = processEnrollCapture(frame, facesToDraw, curRatio);
                                    }
                                } else { 
                                    // 중앙이 아님 -> 거리 함수가 실행 안 됐으므로 여기서 안내
                                    if (!isProcessing) {
                                        showGuideText("얼굴을 중앙에 맞춰주세요");
                                        messageHandled = true; // 내가 띄웠음 표시
                                    }
                                }
                            } else {
                                // ★ [수정 2] 얼굴이 안 보이면? 바로 에러 띄우지 말고 카운트만 증가
                                missingFaceCount++;
                            }
                            
                            // ★ [최종 수정] 
                            // 얼굴이 없거나, 중앙이 아니어서 위에서 처리가 안 됐을 때...
                            // "얼굴이 진짜로 오랫동안(약 0.5초) 사라졌을 때만" 초기화합니다.
                            if (!messageHandled && !isProcessing) {
                                // 10프레임(약 0.5초) 이상 얼굴이 안 보일 때만 리셋
                                if (missingFaceCount > 10) {
                                    showGuideText("얼굴을 화면에 비춰주세요");
                                    autoCountdownActive = false;
                                }
                                // missingFaceCount가 10 이하일 때는 
                                // "잠깐 튄 것"으로 간주하고 아무것도 하지 않음 (카운트다운 유지됨)
                            }
                        }
                    }
                } else if (tabIdx == 1) { // 인식 탭
                    process1NRecognition(frame, facesToDraw); 
                }

                long now = System.nanoTime();
                if (now - lastUi >= uiInterval) {
                    lastUi = now;
                    cropAndResize(frame, displayFrame, previewW, previewH);
                    cachedImage = updateImage(displayFrame, cachedImage); 
                    if (cachedImage != null) {
                        BufferedImage drawTarget = cachedImage;

                        // ========================================================
                        // ★★★ [핵심 수정] 모델 로딩 중이면 로딩 화면 덮어씌우기 ★★★
                        // ========================================================
                        if (!modelReady) {
                            // 카메라 영상 위에 검은색 로딩 화면을 덧그림
                            drawLoadingScreen(drawTarget);
                        } 
                        else {
                            // [로딩 완료 후] 정상적인 오버레이 (테두리, 가이드 등)
                            if (tabIdx == 0) drawTarget = drawEnrollOverlay(drawTarget, maxRatio, previewW, previewH);
                            drawTarget = drawTextOverlay(drawTarget, currentGuideMessage);
                        }
                        // ========================================================
                        
                        final BufferedImage finalImg = drawTarget;
                        SwingUtilities.invokeLater(() -> {
                            if (tabIdx == 0) updateLabelIcon(enrollVideoLabel, finalImg);
                            else if (tabIdx == 1) updateLabelIcon(recognizeVideoLabel, finalImg);
                            else updateLabelIcon(securityVideoLabel, finalImg);
                        });
                    }
                }
                try { Thread.sleep(5); } catch (Exception e) {}

            } catch (Exception e) {
                try { Thread.sleep(100); } catch (Exception ex) {}
            }
        } 

        // [종료 시 해제]
        frame.release();
        if (displayFrame != null) displayFrame.release();
        if (analysisFrame != null) analysisFrame.release();
        if (lastFrameForCapture != null) lastFrameForCapture.release();
        if (detectGrayFrame != null) detectGrayFrame.release();
        if (detectSmallFaces != null) detectSmallFaces.deallocate();
        if (cap != null) cap.release(); // 카메라 해제 추가
        if (pendingReadTask != null) pendingReadTask.cancel(true);
        frameReader.shutdownNow();
    }

    // ★★★ [신규] UI 메모리 누수 방지용 헬퍼 메서드 ★★★
    // 매번 new ImageIcon을 만들지 않고, 기존 아이콘에 이미지만 갈아끼웁니다.
    private void updateLabelIcon(JLabel label, BufferedImage img) {
        if (label.getIcon() instanceof ImageIcon) {
            ((ImageIcon) label.getIcon()).setImage(img); // 기존 객체 재사용
            label.repaint();
        } else {
            label.setIcon(new ImageIcon(img)); // 최초 1회만 생성
        }
    }
    
    // [신규] 카메라 끊김 시 화면을 검게 칠하고 경고 문구 그리기
    private void drawCameraErrorScreen(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        
        // 1. 전체 검은색 칠하기
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());

        // 2. 테두리 빨간색 (경고 느낌)
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(10)); // 두꺼운 테두리
        g.drawRect(0, 0, img.getWidth(), img.getHeight());

        // 3. 중앙 텍스트 (상수 폰트 재사용)
        g.setFont(Style.FONT_HUD); 
        String msg = "카메라 연결 없음";
        FontMetrics fm = g.getFontMetrics();
        int x = (img.getWidth() - fm.stringWidth(msg)) / 2;
        int y = img.getHeight() / 2;
        
        g.drawString(msg, x, y);
        
        g.dispose();
    }
    
    // [신규] 초기 로딩 화면 그리기
    private void drawLoadingScreen(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        
        // 1. 전체 검은색 칠하기
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());

        // 2. 테두리 (파란색 or 노란색으로 '작동 중' 느낌)
        g.setColor(new Color(0, 200, 255)); // 밝은 하늘색
        g.setStroke(new BasicStroke(5));    // 테두리 두께
        g.drawRect(0, 0, img.getWidth(), img.getHeight());

        // 3. 텍스트 설정 (안티앨리어싱 적용)
        setupText(g);
        g.setFont(Style.FONT_HUD); 
        g.setColor(Color.WHITE);
        
        String msg = "안면인식 보안 프로그램 로드 중";
        String subMsg = "잠시만 기다려주세요...";
        
        FontMetrics fm = g.getFontMetrics();
        
        // 중앙 정렬 좌표 계산
        int x = (img.getWidth() - fm.stringWidth(msg)) / 2;
        int y = img.getHeight() / 2 - 20; // 살짝 위로
        g.drawString(msg, x, y);
        
        // 보조 문구 (작게)
        g.setFont(Style.FONT_GUIDE);
        fm = g.getFontMetrics();
        int sx = (img.getWidth() - fm.stringWidth(subMsg)) / 2;
        int sy = y + 40;
        g.setColor(Color.LIGHT_GRAY);
        g.drawString(subMsg, sx, sy);
        
        g.dispose();
    }

    // 반환값이 boolean으로 변경됨 (true: 메시지 출력함 / false: 아무 말 안 함)
    private boolean processEnrollCapture(Mat frame, List<Rectangle> faces, double currentRatio) {
        
        // 1. 처리 중이거나 얼굴 없으면 -> 메시지 처리 안 함 (false)
        // (단, 카운트다운 중에 얼굴이 사라진 특수 상황은 예외)
        if (isProcessing || faces.size() == 0) {
            if (autoCountdownActive && faces.size() == 0) {
                autoCountdownActive = false;
                showGuideText("취소됨: 얼굴이 사라졌습니다.");
                return true; // ★ 메시지 띄웠으니 true 반환
            }
            return false; // 아무것도 안 했으니 false
        }

        long now = System.currentTimeMillis();
        int curPct = (int)(currentRatio * 100); 
        int minPct = (int)(minFaceRatio * 100);
        int maxPct = (int)(maxFaceRatio * 100);

        boolean isDistanceGood = (currentRatio >= minFaceRatio && currentRatio <= maxFaceRatio);
        boolean isCooldownOver = (now - lastAutoCaptureTime) > autoCaptureCooldownMs;

        if (isDistanceGood) {
            // 2. 거리 좋음: 쿨타임 대기 중
            if (!isCooldownOver) {
                showGuideText(String.format("잠시 대기 중... (%d%%)", curPct));
                return true; // ★ 메시지 띄움
            }

            // 3. 거리 좋음: 카운트다운 시작
            if (!autoCountdownActive) {
                autoCountdownActive = true;
                autoCountdownStartMs = now;
                showGuideText(String.format("좋습니다! (%d%%) 움직이지 마세요", curPct));
                return true; // ★ 메시지 띄움
            } else {
                // 4. 거리 좋음: 카운트다운 진행 중
                long elapsed = now - autoCountdownStartMs;
                long targetTime = autoCountdownSec * 1000L;

                if (elapsed >= targetTime) {
                    // 촬영 시점!
                    autoCountdownActive = false;
                    isProcessing = true; 
                    lastAutoCaptureTime = now;
                    showGuideText("찰칵! 촬영 중...");
                    handleEnrollment(frame, faces);
                    return true; // ★ 메시지 띄움
                } else {
                    int remain = (int) Math.ceil((targetTime - elapsed) / 1000.0);
                    //showGuideText(String.format("움직이지 마세요 %d초... (%d%%)", remain, curPct));
                    return true; // ★ 메시지 띄움
                }
            }
        } else {
            // 5. 거리 안 맞음
            autoCountdownActive = false;
            if (currentRatio < minFaceRatio) {
                showGuideText(String.format("앞으로 오세요 (%d%% < %d%%)", curPct, minPct));
            } else {
                showGuideText(String.format("뒤로 가세요 (%d%% > %d%%)", curPct, maxPct));
            }
            return true; // ★ 메시지 띄움 (앞/뒤 안내)
        }
    }

    // ★★★ 등록 취소 처리 로직 ★★★
    private void cancelEnrollment() {

        if (enrollmentFuture != null && currentEnrollTarget != null) {
            String target = currentEnrollTarget;
            //System.out.println("[시스템] 사용자 취소 요청: " + target);
            enrollmentFuture.complete("FA)REG:" + target);
        }

        // 1. [즉시 실행] 타이머 멈추고 안내 문구 띄우기
        SwingUtilities.invokeLater(() -> {
            stopCountdown();
            currentEnrollTarget = null;
            enrollmentFuture = null;
            showGuideText("등록이 취소되었습니다."); // 이 문구가 1초간 보임
        });

        // 2. [비동기 지연] 1초 뒤에 탭 이동
        javax.swing.Timer backTimer = new javax.swing.Timer(1000, e -> {
            tabbedPane.setSelectedIndex(2);
            ((javax.swing.Timer) e.getSource()).stop();
        });
        backTimer.setRepeats(false);
        backTimer.start();

    }

    // [수정됨] 1:N 인식 로직 (판독 불가 처리 포함)
    // [수정됨] 비동기 1:N 인식 로직 (메인 루프를 절대 멈추지 않음)
    private void process1NRecognition(Mat frame, List<Rectangle> faces) {

        // ★★★ [수정] 0. 안전장치 추가: 엔진 로딩 전이면 즉시 리턴 (Crash 방지) ★★★
        if (faceEngine == null || !modelReady) {
            return; 
        }

        // 1. 기본 조건 체크 (얼굴 없으면 패스)
        if (faces.size() == 0) {
            // 얼굴 없으면 카운트 초기화 (2초 이상 안 보일 때)
            if (System.currentTimeMillis() - lastRecognizeTime > 2000) {
                consecutiveSuccessCount = 0;
                lastCandidateName = "";
            }
            return;
        }

        // 2. 이미 문 열린 상태면 중단
        if (!lastIdentifiedName.isEmpty()) {
            return;
        }

        // 3. [중요] 이미 인식 작업이 돌아가는 중이면 이번 프레임은 버림 (렉 방지)
        if (isRecognizingNow.get()) {
            return; 
        }

        // 4. 타이밍 체크 (너무 자주 요청하지 않도록)
        long now = System.currentTimeMillis();
        
        // 탭 진입 후 2초간 대기 (안내 멘트 유지용)
        if (now - recognitionStartMs < 2000) {
            // showGuideText("인식 준비 중..."); // 삭제됨 (기존 멘트 유지)
            return;
        }
        
        // 인식 쿨타임 (0.2초)
        if (now - lastRecognizeTime < 200) { 
            return;
        }

        // -------------------------------------------------------------
        // 여기서부터 비동기 준비
        // -------------------------------------------------------------
        Rectangle r = faces.get(0);

        // 박스 크기 확장 및 유효성 검사는 메인 스레드에서 빠르게 처리
        int pad = (int) (r.width * 0.1);
        int x = Math.max(0, r.x - pad);
        int y = Math.max(0, r.y - pad);
        int w = Math.min(frame.cols() - x, r.width + pad * 2);
        int h = Math.min(frame.rows() - y, r.height + pad * 2);
        
        // 너무 작거나 비율 이상하면 패스
        if (r.width < 80 || r.height < 80) return; // J4125 고려 크기 완화
        double ratio = (double) r.width / r.height;
        if (ratio < 0.6 || ratio > 1.3) return;

        Rect paddedRect = new Rect(x, y, w, h);
        
        // ★★★ [핵심] 얼굴 이미지만 복제(Clone)해서 스레드로 넘김 ★★★
        // 메인 frame은 계속 바뀌므로 반드시 복제해야 함
        Mat faceCrop;
        try (Mat roi = new Mat(frame, paddedRect)) {
            faceCrop = roi.clone();
        } // 여기서 roi는 자동으로 release() 됩니다.

        // -------------------------------------------------------------
        // 별도 스레드에게 "이거 누군지 알아봐" 하고 던짐 (즉시 리턴)
        // -------------------------------------------------------------
        recognizerThread.submit(() -> {
            try {
                isRecognizingNow.set(true); // "작업 중" 깃발
                lastRecognizeTime = System.currentTimeMillis();

                // [무거운 작업 2] 특징 추출 (CPU 많이 씀)
                // (faceEngine이 null이 아닌 것은 위 0번에서 보장됨)
                float[] vec = faceEngine.extractFeature(faceCrop);
                
                if (vec != null) {
                    // [무거운 작업 3] DB 검색
                    String foundName = faceEngine.identify(vec);
                    
                    // UI 업데이트는 반드시 invokeLater로
                    SwingUtilities.invokeLater(() -> handleRecognitionResult(foundName));
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                faceCrop.release(); // 메모리 해제
                isRecognizingNow.set(false); // "작업 끝" 깃발 해제
            }
        });
    }

    // [신규] 인식 결과를 UI 스레드에서 처리하는 헬퍼 메서드
    private void handleRecognitionResult(String foundName) {
        
        // 판독 불가 처리
        if ("!AMBIGUOUS".equals(foundName)) {
            showGuideText("판독 불가: 중복/유사 얼굴");
            consecutiveSuccessCount = 0;
            lastCandidateName = "";
            return;
        }

        if (foundName != null) {
            if (foundName.equals(lastCandidateName)) {
                consecutiveSuccessCount++;
            } else {
                lastCandidateName = foundName;
                consecutiveSuccessCount = 1;
            }

            // 3회 연속 성공 시 최종 승인
            if (consecutiveSuccessCount >= 3) {
                lastIdentifiedName = foundName;
                showGuideText("인증 성공: " + getFriendlyName(foundName));
                
                String msgLog = "SC)REC:" + foundName;
                
                // ★ [수정] 메인 스레드가 변수를 낚아채서 null로 만들기 전에, 
                // 내 작업 공간(지역 변수)으로 안전하게 복사해 옵니다.
                CompletableFuture<String> tempFuture = recognitionFuture;
                
                // 복사해 온 안전한 변수로 완료 처리를 합니다.
                if (tempFuture != null && !tempFuture.isDone()) {
                    tempFuture.complete(msgLog);
                }

                consecutiveSuccessCount = 0;
                lastCandidateName = "";

                // 1.5초 후 복귀 (타이머 사용)
                javax.swing.Timer backTimer = new javax.swing.Timer(1500, e -> {
                    tabbedPane.setSelectedIndex(2);
                    lastIdentifiedName = "";
                    ((javax.swing.Timer)e.getSource()).stop();
                });
                backTimer.setRepeats(false);
                backTimer.start();
            }
        } else {
            // 실패
            consecutiveSuccessCount = 0;
            lastCandidateName = "";
            showGuideText("등록되지 않은 사용자입니다.");
        }
    }

    // ★★★ [신규] 인식 취소 로직 ★★★
    private void cancelRecognition() {

        if (recognitionFuture != null) {
            System.out.println("[시스템] 사용자 인식 취소 요청");
            recognitionFuture.complete("FA)REC:Cancelled");
        }

        // 1. [즉시 실행] 타이머 멈추고 안내 문구 띄우기
        SwingUtilities.invokeLater(() -> {
            stopCountdown();
            recognitionFuture = null;
            showGuideText("인식이 취소되었습니다."); // 이 문구가 1초간 보임
        });

        // 2. [비동기 지연] 1초 뒤에 탭 이동
        javax.swing.Timer backTimer = new javax.swing.Timer(1000, e -> {
            tabbedPane.setSelectedIndex(2);
            ((javax.swing.Timer) e.getSource()).stop();
        });
        backTimer.setRepeats(false);
        backTimer.start();

    }

	// ===============================================================
	// ★★★ [복구] 이미지 선명도 측정 메서드 (이게 없어서 에러 발생) ★★★
	// ===============================================================
    private double calculateBlurScore(Mat src) {

    	// 자원 해제를 위해 try-with-resources 구문 사용
        try (Mat laplacian = new Mat(); Mat mean = new Mat(); Mat stdDev = new Mat()) {
        	// 1. 라플라시안 변환 (가장자리 검출)
            org.bytedeco.opencv.global.opencv_imgproc.Laplacian(
                    src,
                    laplacian,
                    org.bytedeco.opencv.global.opencv_core.CV_64F
            );

            // 2. 평균과 표준편차 계산
            org.bytedeco.opencv.global.opencv_core.meanStdDev(laplacian, mean, stdDev);
            // 3. 분산(Variance) = 표준편차의 제곱
            // (Indexer를 사용해 0번째 값을 꺼내옴)
            double sigma = stdDev.createIndexer().getDouble(0);
            return sigma * sigma; // 점수가 높을수록 선명함
        } catch (Exception e) {
            e.printStackTrace();
            return 0.0; // 에러 나면 0점 처리
        }
    }

    private void handleEnrollment(Mat frame, List<Rectangle> faces) {
        // [수정 1] 등록 대상자(ID) 체크를 '가장 먼저' 수행
        // 이유: 등록이 완료되어 userId가 null이 되면, 아래 얼굴 감지 실패 메시지도 안 뜨게 하기 위함
        String userId = currentEnrollTarget;
        if (userId == null || userId.isEmpty()) {
            return; // 대상자가 없으면 아무 메시지도 뿌리지 않고 조용히 리턴
        }
        userId = userId.trim();

        // 1. 얼굴 감지 실패 시
        if (faces.size() == 0) {
            showGuideText("등록 실패: 얼굴을 찾을 수 없습니다.");
            isProcessing = false; // (선택) 실패 시 해제
            return;
        }

        try {
            Rectangle r = faces.get(0);
            // 이미지 잘라내기 (ROI)
            int pad = (int) (r.width * 0.1);
            int x = Math.max(0, r.x - pad);
            int y = Math.max(0, r.y - pad);
            int w = Math.min(frame.cols() - x, r.width + pad * 2);
            int h = Math.min(frame.rows() - y, r.height + pad * 2);
            Rect paddedRect = new Rect(x, y, w, h);
            
            try (Mat faceROI = new Mat(frame, paddedRect)) {
                
                // =========================================================
                // [수정] 역광 보정 및 수동 메모리 해제 (안전 제일)
                // =========================================================
                
                Mat analysisFace = null; // 변수 미리 선언
                boolean isBlurry = false;

                try {
                    analysisFace = new Mat(); // 객체 생성
                    
                    // 1. 흑백 변환
                    org.bytedeco.opencv.global.opencv_imgproc.cvtColor(faceROI, analysisFace, COLOR_BGR2GRAY);
                    
                    // 2. 히스토그램 평활화 (어두운 곳 명암비 강제 향상)
                    org.bytedeco.opencv.global.opencv_imgproc.equalizeHist(analysisFace, analysisFace);
                    
                    // 3. 흔들림 측정
                    double blurScore = calculateBlurScore(analysisFace);
                    
                    // 기준값 (50점 미만이면 흔들림)
                    if (blurScore < 50) {
                        isBlurry = true;
                    }
                    
                } catch (Exception e) {
                    e.printStackTrace();
                    isBlurry = true; // 에러나면 안전하게 흔들린 걸로 처리
                } finally {
                    // ★★★ [수동 해제] 무슨 일이 있어도 여기서 메모리 삭제 ★★★
                    if (analysisFace != null) {
                        analysisFace.release(); 
                        analysisFace = null;
                    }
                }

                // 결과 처리 (메모리 해제 후 판정)
                if (isBlurry) {
                    showGuideText("등록 실패: 사진이 흔들렸습니다.");
                    isProcessing = false;
                    return;
                }

                // [검사 3] 밝기 체크
                Scalar meanScalar = org.bytedeco.opencv.global.opencv_core.mean(faceROI);
                double brightness = (meanScalar.get(0) + meanScalar.get(1) + meanScalar.get(2)) / 3.0;
                if (brightness < 40) {
                    showGuideText("등록 실패: 너무 어둡습니다.");
                    isProcessing = false;
                    return;
                }
                if (brightness > 220) {
                    showGuideText("등록 실패: 너무 밝습니다.");
                    isProcessing = false;
                    return;
                }

                // [검사 4] 특징 추출
                float[] vector = faceEngine.extractFeature(faceROI);
                if (vector == null) {
                    showGuideText("등록 실패: 얼굴 특징을 찾을 수 없습니다.");
                    isProcessing = false;
                    return;
                }

                // =========================================================
                // ★ 모든 합격 기준 통과! -> 저장 수행 ★
                // =========================================================
                
                // 1. 파일 저장 로직 (생략 없이 그대로 사용)
                File dir = new File(DATASET_ROOT, userId);
                if (!dir.exists()) dir.mkdirs();

                File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jpg"));
                if (files != null && files.length >= 50) {
                    faceEngine.removeOldestVector(userId);
                }

                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                File file = new File(dir, "face_" + ts + ".jpg");
                int dup = 1;
                while (file.exists()) {
                    file = new File(dir, "face_" + ts + "_" + (dup++) + ".jpg");
                }

                org.bytedeco.opencv.global.opencv_imgcodecs.imwrite(file.getAbsolutePath(), faceROI);

                // 2. 메모리 및 캐시 등록
                faceEngine.registerUserInMemory(userId, vector);
                faceEngine.saveUserCacheImmediate(DATASET_ROOT, userId);

                // 3. 성공 메시지 표시
                showGuideText("얼굴 등록 완료!"); 

                // ★ [수정 2] 성공 즉시 대상자를 null로 설정하여 중복 진입 차단 ★
                // 이렇게 하면 1.5초 대기하는 동안 다음 프레임이 들어와도 맨 위 if문에서 걸러짐
                currentEnrollTarget = null; 

                // 4. 관제 전송
                String msgLog = "SC)REG:" + userId;
                if (enrollmentFuture != null && !enrollmentFuture.isDone()) {
                    enrollmentFuture.complete(msgLog);
                }

                // 5. 잠시 후 화면 전환
                new Thread(() -> {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {}

                    SwingUtilities.invokeLater(() -> {
                        tabbedPane.setSelectedIndex(2);
                        isProcessing = false; // 화면 넘어갈 때 플래그 해제
                    });
                }).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
            showGuideText("등록 오류 발생");
            isProcessing = false;
        }
    }

	// ===============================================================
	// ★★★ [최종 프로토콜] Raw String + 0x00 종결자 방식 ★★★
	// ===============================================================
	// [송신] 문자열을 있는 그대로 보내고, 마지막에 0x00(NULL) 한 바이트 추가
    private void sendPacket(OutputStream out, String msg) throws IOException {
        if (msg == null) {msg = "";}
        
        logger.log("SEND", msg);
        
        // 1. 문자열을 바이트로 변환 (UTF-8)
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        // 2. 데이터 전송
        out.write(data);
        // 3. 끝 표시 (NULL Byte) 전송
        out.write(0x00);
        out.flush();
    }
    
    private void loadDebugConfig() {
        // [수정] 실행 위치 기준 상위 폴더 계산 (BASE_PATH 활용)
        File parentDir = new File(BASE_PATH).getParentFile();
        File iniFile = new File(parentDir, "SETUP.INI");

        // 상위에 없으면 현재 폴더 확인
        if (!iniFile.exists()) {
            iniFile = new File(BASE_PATH, "SETUP.INI");
        }

        // ★ [중요] 파일이 없으면 그냥 리턴 (절대 만들지 않음)
        if (!iniFile.exists()) {
            // System.out.println("[시스템] SETUP.INI 없음 (기본값 사용)");
            return; 
        }

        try {
            boolean inSetupSection = false;
            // 한글 깨짐 방지 MS949
            for (String line : Files.readAllLines(iniFile.toPath(), java.nio.charset.Charset.forName("MS949"))) {
                line = line.trim();
                
                if (line.equalsIgnoreCase("[SETUP]")) {
                    inSetupSection = true;
                    continue;
                }
                if (line.startsWith("[") && !line.equalsIgnoreCase("[SETUP]")) {
                    inSetupSection = false;
                }

                if (inSetupSection && line.toUpperCase().startsWith("DEBUG")) {
                    String[] parts = line.split("=", 2);
                    if (parts.length > 1) {
                        String val = parts[1].trim();
                        if ("1".equals(val)) {
                            logger.isDebugMode = true;
                            logger.log("SYS", "[설정] 디버그 모드: ON");
                        } else {
                            logger.log("SYS", "[설정] 디버그 모드: OFF");
                            logger.isDebugMode = false;
                        }
                        return;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[설정 읽기 실패] " + e.getMessage());
        }
    }
    
    // [신규] 시스템이 바쁜지(등록/인식/삭제 중) 확인하는 통합 메서드
    private boolean isSystemBusy() {
        return (enrollmentFuture != null && !enrollmentFuture.isDone()) || // 등록 중
               (recognitionFuture != null && !recognitionFuture.isDone()) || // 인식 중
               isDeleting; // 삭제 중
    }

	// [수정] OutputStream을 직접 제어하여 "OK 전송 -> 대기 -> 결과 전송" 구현
	// [수정] 응답 코드 변경 (OK, NK, SC, FA, ER)
	// [수정] 등록 타임아웃/에러 시에도 1.5초 후 보안 모드로 자동 복귀
	// [수정] 타이머 시작(startCountdown) 및 종료(stopCountdown) 로직 추가
    public void processCommand(String command, OutputStream out) throws IOException {
        
        // ------------------------------------------------------------
        // 1. [REG] 얼굴 등록
        // ------------------------------------------------------------
        if (command.startsWith("REG:")) {
            try {
                // [방어 로직] 로딩 중이거나, 이미 다른 작업 중이면 -> 그냥 무시 (반응 X)
                // 이유: 이상한 패킷 보내면 웹뷰가 오작동하므로 차라리 아무것도 안 하는 게 낫음
                if (!modelReady || isSystemBusy()) {
                    logger.log("WARN", "[TCP] 등록 요청 무시됨 (Loading or Busy)");
                    return; 
                }

                String targetData = command.substring(4).trim();
                if (targetData.isEmpty()) {
                    sendPacket(out, "NK)REG:Target Empty");
                    return;
                }
                if (!targetData.matches("\\d{5}_\\d{5}")) {
                    sendPacket(out, "NK)REG:Invalid Format");
                    return;
                }

                sendPacket(out, "OK)REG:" + targetData);
                
                enrollmentFuture = new CompletableFuture<>();
                final String finalTarget = targetData;

                SwingUtilities.invokeLater(() -> {
                    bringToFrontStrong();
                    tabbedPane.setSelectedIndex(0);
                    currentEnrollTarget = finalTarget;
                    enrollmentStartMs = System.currentTimeMillis();
                    isProcessing = false;
                    playEnrollGuideSound();
                    startCountdown(31, 3.5, enrollTimerLabel);
                });

                try {
                    String result = enrollmentFuture.get(34, TimeUnit.SECONDS);
                    sendPacket(out, result);
                } catch (Exception e) {
                    sendPacket(out, "FA)REG:Timeout");
                    SwingUtilities.invokeLater(() -> {
                        currentEnrollTarget = null;
                        showGuideText("등록 실패 (시간 초과)");
                    });
                    new Thread(() -> {
                        try { Thread.sleep(1500); } catch (Exception ex) {}
                        SwingUtilities.invokeLater(() -> tabbedPane.setSelectedIndex(2));
                    }).start();
                } finally {
                    stopCountdown();
                    enrollmentFuture = null; // 작업 끝
                }
                return;
            } catch (Exception e) {
                sendPacket(out, "ER)REG:" + e.getMessage());
                return;
            }
        }

        // ------------------------------------------------------------
        // 2. [SHOW] 창 제어
        // ------------------------------------------------------------
        if (command.startsWith("SHOW=")) {
            String val = command.substring(5).trim();
            if ("1".equals(val)) {
                SwingUtilities.invokeLater(this::bringToFrontStrong);
                sendPacket(out, "SC)SHOW:1");
            } else if ("0".equals(val)) {
                SwingUtilities.invokeLater(() -> frame.setExtendedState(JFrame.ICONIFIED));
                sendPacket(out, "SC)SHOW:0");
            } else {
                sendPacket(out, "FA)SHOW:Invalid Value");
            }
            return;
        }

        // ------------------------------------------------------------
        // 3. [DEL] 삭제
        // ------------------------------------------------------------
        if (command.startsWith("DEL:")) {
            try {
                // [방어 로직] 로딩 중이거나 바쁘면 -> 무시 (반응 X)
                if (!modelReady || isSystemBusy()) {
                    logger.log("WARN", "[TCP] 삭제 요청 무시됨 (Loading or Busy)");
                    return; 
                }

                String target = command.substring(4).trim();
                if (!target.matches("\\d{5}_\\d{5}")) {
                    sendPacket(out, "NK)DEL:Invalid Format");
                } else {
                    isDeleting = true; // 깃발 꽂기
                    try {
                        boolean deleted = deleteUnitData(target);
                        if (deleted) {
                            sendPacket(out, "SC)DEL:" + target);
                        } else {
                            sendPacket(out, "FA)DEL:Not Found");
                        }
                    } finally {
                        isDeleting = false; // 깃발 내리기
                    }
                }
            } catch (Exception e) {
                isDeleting = false;
                sendPacket(out, "ER)DEL:" + e.getMessage());
            }
            return;
        }

        // ------------------------------------------------------------
        // 4. 단일 명령어 (REC, CAP, EXIT)
        // ------------------------------------------------------------
        switch (command) {

            case "REC":
                // 1. [로딩 중] 아직 준비 안 됨 -> '실패(FA)' 전송
                // 웹뷰: "FA 받았다 -> 실패 팝업 띄우고 5초 뒤 닫아야지" (정상 작동)
                if (!modelReady) {
                    sendPacket(out, "FA)REC"); 
                    SwingUtilities.invokeLater(() -> showGuideText("시스템 로딩 중..."));
                    return; 
                }

                // 2. [바쁨] 이미 인식 창 떠있음 -> '무시' (아무 패킷도 안 보냄)
                // 웹뷰: (첫 번째 요청의 응답을 기다리는 중이므로 아무 영향 없음)
                if (recognitionFuture != null && !recognitionFuture.isDone()) {
                    logger.log("WARN", "[TCP] 중복된 인식 요청 무시함 (Busy)");
                    return; 
                }

                // --- 정상 진행 ---
                sendPacket(out, "OK)REC");
                
                recognitionFuture = new CompletableFuture<>();

                SwingUtilities.invokeLater(() -> {
                    bringToFrontStrong();
                    tabbedPane.setSelectedIndex(1);
                    currentEnrollTarget = null;
                    recognitionStartMs = System.currentTimeMillis();
                    startCountdown(21, 3.5, recognizeTimerLabel);
                });

                try {
                    String result = recognitionFuture.get(24, TimeUnit.SECONDS);
                    sendPacket(out, result);
                } catch (Exception e) {
                    sendPacket(out, "FA)REC"); // 타임아웃 실패
                    showGuideText("인식 실패 (시간 초과)");
                    new Thread(() -> {
                        try { Thread.sleep(1500); } catch (Exception ex) {}
                        SwingUtilities.invokeLater(() -> tabbedPane.setSelectedIndex(2));
                    }).start();
                } finally {
                    stopCountdown();
                    recognitionFuture = null; // 작업 끝
                }
                break;

            case "CAP":
                sendPacket(out, "OK)CAP");
                securityCaptureFuture = new CompletableFuture<>();
                requestSecurityCapture();
                try {
                    String result = securityCaptureFuture.get(5, TimeUnit.SECONDS);
                    sendPacket(out, result);
                } catch (Exception e) {
                    sendPacket(out, "FA)CAP:Timeout");
                } finally {
                    securityCaptureFuture = null;
                }
                break;

            case "EXIT_OK":
                sendPacket(out, "SC)EXIT_OK");
                new Thread(() -> {
                    isExpectedExit = true;
                    logger.log("SYS", "[종료] TCP 원격 종료 명령(EXIT_OK) 수행");
                    stopCamera();
                    SimpleTcpServer.stop();
                    System.exit(0);
                }).start();
                break;

            default:
                sendPacket(out, "NK)" + command);
                break;
        }
    }

    private void requestSecurityCapture() {
        securityCaptureRequested = true;
    }

    // 저장 형식: sec_20260119_143005.jpg
    private void performSecurityCapture() {

        if (lastFrameForCapture == null || lastFrameForCapture.empty()) {
            if (securityCaptureFuture != null) {
                securityCaptureFuture.complete("FA)CAP:No Frame");
            }
            return;
        }

        try {
            // 1. 날짜_시간 포맷 (초 단위까지)
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

            // ★★★ [수정] 꼬리표 없이 깔끔하게 'sec_날짜_시간.jpg'로 고정 ★★★
            File f = new File(this.securityPath, "sec_" + ts + ".jpg");

            // (선택사항) 만약 같은 초에 찍혀서 파일이 이미 존재하면 덮어쓰거나, _1 등을 붙여야 함
            // "쓸데없이 붙이지 말라"고 하셨으므로 덮어쓰기 로직으로 간결하게 갑니다.
            
            // 폴더 없으면 생성
            if (!f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
            
            // =============================================================
            // [추가] 파일 개수 제한 로직 (하드코딩: 1000개 유지)
            // =============================================================
            File dir = f.getParentFile();
            // .jpg 파일만 목록으로 가져옴
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jpg"));

            // 파일이 1000개 이상이면?
            if (files != null && files.length >= 1000) { 
                // 날짜순 정렬 (오래된 게 앞쪽으로)
                java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
                
                // 가장 오래된 파일 삭제 (공간 확보)
                // (만약 1000개가 넘게 쌓여있으면 루프 돌려서 여러 개 지워도 됨, 여기선 1개만 지움)
                if (files[0].delete()) {
                    // System.out.println("오래된 캡처 자동 삭제: " + files[0].getName());
                }
            }

            // 2. 이미지 저장
            boolean saved = org.bytedeco.opencv.global.opencv_imgcodecs.imwrite(f.getAbsolutePath(), lastFrameForCapture);

            if (saved) {
                // System.out.println("[보안캡쳐] 저장됨: " + f.getName()); // 로그 확인용
                if (securityCaptureFuture != null) {
                    securityCaptureFuture.complete("SC)CAP");
                }
            } else {
                if (securityCaptureFuture != null) {
                    securityCaptureFuture.complete("FA)CAP:Save Failed");
                }
            }
        } catch (Exception e) {
            if (securityCaptureFuture != null) {
                securityCaptureFuture.complete("ER)CAP:" + e.getMessage());
            }
        }
    }
    
    private void loadSavePath() {
        // [수정] 상위 폴더 우선 검색
        File parentDir = new File(BASE_PATH).getParentFile();
        File iniFile = new File(parentDir, "SETUP.INI");
        
        if (!iniFile.exists()) {
            iniFile = new File(BASE_PATH, "SETUP.INI");
        }

        // ★ [중요] SETUP.INI가 없으면 기본값 쓰고 종료 (파일 생성 X)
        if (!iniFile.exists()) {
            logger.log("SYS", "[설정] SETUP.INI 없음. 기본 저장 경로 사용");
            // 기본 경로는 생성해둠 (저장은 해야 하니까)
            if (!this.securityCapturesDir.exists()) {
                this.securityCapturesDir.mkdirs();
            }
            return;
        }

        try {
            boolean inSaveSection = false;
            
            for (String line : Files.readAllLines(iniFile.toPath(), java.nio.charset.Charset.forName("MS949"))) {
                line = line.trim();
                
                if (line.equalsIgnoreCase("[SAVE_SETUP]")) {
                    inSaveSection = true;
                    continue;
                }
                if (line.startsWith("[") && !line.equalsIgnoreCase("[SAVE_SETUP]")) {
                    inSaveSection = false;
                }

                if (inSaveSection && line.toUpperCase().startsWith("PATH=")) {
                    String[] parts = line.split("=", 2);
                    if (parts.length > 1) {
                        String newPath = parts[1].trim();
                        if (!newPath.isEmpty()) {
                            this.securityPath = newPath;
                            
                            // [경로 처리] 절대 경로인지 상대 경로인지 확인
                            File testFile = new File(newPath);
                            if (testFile.isAbsolute()) {
                                this.securityCapturesDir = testFile;
                            } else {
                                // 상대 경로면 BASE_PATH 기준
                                this.securityCapturesDir = new File(BASE_PATH, newPath);
                            }

                            // ★ 설정 파일은 안 건드리지만, 사진 저장할 폴더는 없으면 만들어야 함
                            if (!this.securityCapturesDir.exists()) {
                                this.securityCapturesDir.mkdirs();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[설정 오류] 경로 로드 실패: " + e.getMessage());
        }
    }

    // [핵심] 특정 세대(동/호)의 모든 데이터(파일+메모리) 삭제
    // [개선됨] 멱등성(Idempotency) 적용: 데이터가 이미 없어도 '성공'으로 처리
    private boolean deleteUnitData(String unitCode) {
        File dir = new File(DATASET_ROOT, unitCode);

        try {
            // 1. 파일 시스템 삭제
            if (dir.exists() && dir.isDirectory()) {
                deleteRecursively(dir); // 폴더 안의 모든 사진 + 폴더 자체 삭제
                if (logger != null) {
                    logger.log("SYS", "[삭제] 파일 데이터 삭제 완료: " + unitCode);
                }
            }

            // 2. 메모리 DB(엔진)에서도 삭제
            if (faceEngine != null) {
                boolean memoryDeleted = faceEngine.removeUserFromMemory(unitCode);
                if (memoryDeleted && logger != null) {
                    logger.log("SYS", "[삭제] 메모리 데이터 삭제 완료: " + unitCode);
                }
            }

            // 3. [핵심] 결과 판단
            // 파일이 없었든, 메모리에 없었든 여기까지 에러 없이 내려왔다면
            // "해당 사용자의 데이터는 시스템에 확실히 없는 상태" 이므로 성공(true) 처리
            return true; 

        } catch (Exception e) {
            // 파일이 사용 중이거나 권한이 없어서 진짜로 삭제에 실패한 경우만 false 반환
            if (logger != null) {
                logger.log("ER", "[삭제 실패] " + unitCode + " 처리 중 오류: " + e.getMessage());
            }
            return false; 
        }
    }


    // 재귀 삭제 함수 (폴더 안의 내용물부터 지우고 폴더를 지움)
    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File c : files) {
                    deleteRecursively(c);
                }
            }
        }
        f.delete();
    }

    // [수정] 등록 화면 오버레이 (이미지를 얼굴 크기에 맞춰 중앙에 배치)
    // [수정] 등록 화면 오버레이 (카운트다운 숫자 제거됨)
    // [수정] 등록 화면 오버레이 (화면 높이 비례하여 크기 강제 설정)
    private BufferedImage drawEnrollOverlay(BufferedImage img, double maxRatio, int w, int h) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        double visualScale = 0.85;
        int side = (int) (Math.min(w, h) * visualScale);
        int gx = (w - side) / 2;
        int gy = (h - side) / 2;

        if (enrollGuideImage != null) {
            g.drawImage(enrollGuideImage, gx, gy, side, side, null);
        } else {
            // [수정] 상수 사용 (new 제거)
            g.setColor(Style.COLOR_GUIDE_YELLOW); 
            g.setStroke(Style.STROKE_DASHED);     
            g.drawRect(gx, gy, side, side);
            
            // [수정] 상수 사용
            g.setColor(Color.WHITE);
            g.setFont(Style.FONT_GUIDE); 
            
            String txt = "점선 안에 얼굴을 맞춰주세요";
            g.drawString(txt, (w - g.getFontMetrics().stringWidth(txt)) / 2, gy + side + 25);
        }
        g.dispose();
        return img;
    }

    private void stopCamera() {
        running = false;
        try {
            if (cameraThread != null) {
                cameraThread.join(1000);   
            }} catch (Exception e) {
        }
        if (cap != null) {
            cap.release();
        }
    }

    private void bringToFrontStrong() {
    	frame.setExtendedState(JFrame.NORMAL);
    	frame.setVisible(true);
        forceForeground();
        frame.toFront();
        frame.requestFocus();
    }

    // [신규] 텍스트 변경 메서드
    private void showGuideText(String msg) {
        this.currentGuideMessage = msg;
    }

    private BufferedImage drawTextOverlay(BufferedImage img, String msg) {
        if (msg == null || msg.isEmpty()) {
            return img;
        }
        Graphics2D g = img.createGraphics();
        setupText(g);
        
        // [수정] 상수 폰트 사용
        g.setFont(Style.FONT_HUD); 
        
        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(msg);
        int textH = fm.getHeight();
        
        int imgW = img.getWidth();
        int imgH = img.getHeight();
        int x = (imgW - textW) / 2;
        int y = imgH - 60; 
        
        int pad = 20;
        
        // [수정] 상수 컬러 사용
        g.setColor(Style.COLOR_HUD_BG); 
        g.fillRoundRect(x - pad, y - textH, textW + (pad * 2), textH + pad, 20, 20);
        
        g.setColor(Color.WHITE); // Color.WHITE는 자바 기본 상수라 괜찮음
        g.drawString(msg, x, y);
        g.dispose();

        return img;
    }

    private void showError(String t, String m) {
        JOptionPane.showMessageDialog(frame, m, t, JOptionPane.ERROR_MESSAGE);
    }

    // [최적화 핵심] 이미지를 매번 새로 만들지 않고, 기존 메모리에 픽셀만 덮어씁니다.
    // 결과: GC(가비지 컬렉션)가 발생하지 않아 렉이 사라집니다.
    private static BufferedImage updateImage(Mat src, BufferedImage cached) {
        if (src == null || src.empty()) return null;

        int w = src.cols();
        int h = src.rows();
        int type = (src.channels() > 1) ? BufferedImage.TYPE_3BYTE_BGR : BufferedImage.TYPE_BYTE_GRAY;

        // 캐시된 이미지가 없거나 크기가 다르면 새로 생성 (최초 1회만 실행됨)
        if (cached == null || cached.getWidth() != w || cached.getHeight() != h || cached.getType() != type) {
            cached = new BufferedImage(w, h, type);
        }

        // 픽셀 데이터만 고속 복사 (System.arraycopy 수준의 속도)
        try {
            DataBufferByte dataBuffer = (DataBufferByte) cached.getRaster().getDataBuffer();
            byte[] destData = dataBuffer.getData();
            src.data().get(destData); 
        } catch (Exception e) {
            return matToBufferedImage(src); // 에러 발생 시 비상용 구버전 사용
        }

        return cached;
    }

    // [비상용 백업] updateImage에서 문제 생길 때만 호출
    private static BufferedImage matToBufferedImage(Mat src) {
        if (src == null || src.empty()) return null;
        int w = src.cols(), h = src.rows();
        int type = (src.channels() > 1) ? BufferedImage.TYPE_3BYTE_BGR : BufferedImage.TYPE_BYTE_GRAY;
        BufferedImage img = new BufferedImage(w, h, type);
        src.data().get(((DataBufferByte) img.getRaster().getDataBuffer()).getData());
        return img;
    }
    
    // [수정] cachedResizeSize를 사용하여 'new Size'까지 완벽 제거
    private void cropAndResize(Mat src, Mat dest, int targetW, int targetH) {
        if (src == null || src.empty()) return;

        int srcW = src.cols(); 
        int srcH = src.rows();
        
        double srcRatio = (double) srcW / srcH;
        double targetRatio = (double) targetW / targetH;

        int cropW, cropH;

        if (srcRatio > targetRatio) {
            cropH = srcH; 
            cropW = (int) (srcH * targetRatio);
        } else {
            cropW = srcW;
            cropH = (int) (srcW / targetRatio);
        }

        int x = (srcW - cropW) / 2;
        int y = (srcH - cropH) / 2;

        // 좌표 보정 (Clamping)
        if (x < 0) x = 0; if (y < 0) y = 0;
        if (x + cropW > srcW) cropW = srcW - x;
        if (y + cropH > srcH) cropH = srcH - y;

        if (cropW <= 0 || cropH <= 0) return;

        // 1. Rect 재사용 (좌표 설정)
        cachedRoiRect.x(x).y(y).width(cropW).height(cropH);

        try (Mat cropped = new Mat(src, cachedRoiRect)) {
            
            // ★★★ [여기가 수정됨] ★★★ 
            // 아까 선언한 'cachedResizeSize' 그릇에 이번에 쓸 크기를 담습니다.
            // (new Size()를 하지 않습니다!)
            cachedResizeSize.width(targetW).height(targetH);

            org.bytedeco.opencv.global.opencv_imgproc.resize(
                cropped, dest, 
                cachedResizeSize, // <- 재사용된 객체 전달
                0, 0, org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR
            );
        } catch (Exception e) {
        }
    }

    private static void setupText(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    // INI 파일 전체 로드 로직 (수정됨)
    private void loadConfigFromIni() {

        if (!INI_FILE.exists()) {
            createIniTemplate();
        }

        Map<String, String> map = readIniSection(INI_FILE);

        // 2. 윈도우 위치/크기
        try {
            if (map.containsKey("win")) {
                String[] p = map.get("win").split(",");
                iniWinX = Integer.parseInt(p[0].trim());
                iniWinY = Integer.parseInt(p[1].trim());
                iniWinW = Integer.parseInt(p[2].trim());
                iniWinH = Integer.parseInt(p[3].trim());
            }
        } catch (Exception e) {
        }

        // 3. 카메라 해상도
        try {
            if (map.containsKey("frame_size")) {
                String[] p = map.get("frame_size").split(",");
                iniFrameW = Integer.parseInt(p[0].trim());
                iniFrameH = Integer.parseInt(p[1].trim());
            }
        } catch (Exception e) {
        }
        // 4. 화면상 영상 위치
        try {
            if (map.containsKey("preview_rect")) {
                String[] p = map.get("preview_rect").split(",");
                if (p.length == 4) {
                    previewX = Integer.parseInt(p[0].trim());
                    previewY = Integer.parseInt(p[1].trim());
                    previewW = Integer.parseInt(p[2].trim());
                    previewH = Integer.parseInt(p[3].trim());
                }
            }
        } catch (Exception e) {
        }

        // =========================================================
        // ★★★ [수정] 5. 자동 캡처 설정 (이름 통일: min / max) ★★★
        // =========================================================
        try {
        	// [변경] INI 키 이름을 min_capture_ratio 로 읽기 (변수명도 minFaceRatio 사용)
            if (map.containsKey("min_capture_ratio")) {
                minFaceRatio = Double.parseDouble(map.get("min_capture_ratio"));
            } else if (map.containsKey("auto_capture_ratio")) {
            	// (호환성 유지) 혹시 옛날 이름(auto_capture_ratio)이 남아있으면 그걸로 읽음
                minFaceRatio = Double.parseDouble(map.get("auto_capture_ratio"));
            }

            // [신규] 최대 비율 (max_capture_ratio)
            if (map.containsKey("max_capture_ratio")) {
                maxFaceRatio = Double.parseDouble(map.get("max_capture_ratio"));
            }
            //System.out.println("[설정] 얼굴 크기 감지 범위: " + minFaceRatio + " ~ " + maxFaceRatio);
        } catch (Exception e) {
        }
        try {
            if (map.containsKey("auto_capture_countdown_sec")) {
                autoCountdownSec = Integer.parseInt(map.get("auto_capture_countdown_sec"));       
            }} catch (Exception e) {
        }

        // =========================================================
        // 6. 보안 캡처 경로 설정
        if (map.containsKey("security_path")) {
            String path = map.get("security_path").trim();

            if (!path.isEmpty()) {
                securityCapturesDir = new File(path);
                //System.out.println("보안 캡처 경로 설정됨: " + path);
            }
        }

        // 7. 매칭 임계값 로드
        try {
            if (map.containsKey("match_threshold")) {
                double val = Double.parseDouble(map.get("match_threshold"));
                // [보정 로직] 1.0보다 크면 100으로 나눔
                if (val > 1.0) {
                    val = val / 100.0;
                }
                this.iniMatchThreshold = val;
                //System.out.println("[설정 로드] INI 임계값: " + this.iniMatchThreshold);
            }
        } catch (Exception e) {
            //System.err.println("[설정 오류] 매칭 임계값 파싱 실패. 기본값(0.75) 사용.");
        }
    }

 // [수정] 설정 파일이 없을 때, 현재의 최신 face.ini 내용을 그대로 생성하는 함수
    private void createIniTemplate() {
        try (PrintWriter pw = new PrintWriter(INI_FILE)) {
            
            // ==========================================
            // [PART 1] WinRun4J 실행 설정
            // ==========================================
            pw.println("; ==========================================");
            pw.println("; [PART 1] WinRun4J 실행 설정");
            pw.println("; ==========================================");
            pw.println("main.class=com.example.facesec.FaceSecApp");
            pw.println("service.id=FaceSecApp");
            pw.println("service.name=Face Security App");
            pw.println("service.description=Face Recognition Security Application");
            pw.println();
            
            // ★★★ [중요 수정 1] 주석 해제 (작업 경로 고정) ★★★
            // 웹뷰 등 외부에서 실행해도 로그/덤프 파일이 face.exe 위치에 생기도록 강제함
            pw.println("working.directory=.");
            pw.println();
            
            pw.println("classpath.1=face.jar");
            pw.println("vm.location=jre\\bin\\server\\jvm.dll");
            pw.println();

            // ★★★ [중요 수정 2] VM 설정을 이곳으로 통합 (중복 제거) ★★★
            pw.println("; [VM 메모리 및 네트워크 설정]");
            pw.println("vmarg.1=-Xms64m");
            pw.println("vmarg.2=-Xmx1024m");
            pw.println("vmarg.3=-Dsun.java2d.opengl=true");
            pw.println("; IPv4 강제 사용 (통신 에러 해결용)");
            pw.println("vmarg.4=-Djava.net.preferIPv4Stack=true");
            
            // [추가] 메모리 부족(OOM) 시 덤프 생성 옵션
            pw.println("vmarg.5=-XX:+HeapDumpOnOutOfMemoryError");
            // 덤프 파일 경로 지정 (working.directory=. 덕분에 ./logs가 올바르게 인식됨)
            pw.println("vmarg.6=-XX:HeapDumpPath=./logs/error_dump.hprof");
            pw.println();

            // ==========================================
            // [PART 2] 사용자 설정
            // ==========================================
            pw.println("; ==========================================");
            pw.println("; [PART 2] 사용자 설정");
            pw.println("; ==========================================");
            pw.println();
            pw.println("[SETUP]");
            
            pw.println("; 다이얼로그 좌표/크기");
            pw.println("win=0,0,1280,1024");
            pw.println();
            
            pw.println("; 프리뷰 프레임 크기(카메라 요청 해상도)");
            pw.println("frame_size=800,600");
            pw.println();
            
            pw.println("; 다이얼로그 내 카메라 화면 위치 및 크기 (x, y, width, height)");
            pw.println("; preview_rect = X(무시됨), Y(적용됨), Width, Height");
            pw.println("preview_rect=400,100,600,600");
            pw.println();
            
            pw.println("; 매칭 임계값(LBPH distance)");
            pw.println("MATCH_THRESHOLD=0.7");
            pw.println();
            
            pw.println("; FaceSecApp TCP 클라이언트 설정");
            pw.println(";ip=192.168.100.125");
            pw.println(";ip=localhost");
            pw.println("ip=127.0.0.1");
            pw.println();
            
            pw.println(";통신 포트 번호");
            pw.println("port=5001");
            pw.println();
            
            pw.println("; --- 자동 캡처 설정 (거리 제한) ---");
            pw.println("; [변경] 최소 크기 (이것보다 작으면 '너무 멀다' 판단)");
            pw.println("min_capture_ratio=0.27");
            pw.println();
            
            pw.println("; [신규] 최대 크기 (이것보다 크면 '너무 가깝다' 판단)");
            pw.println("max_capture_ratio=0.33");
            pw.println();
            
            pw.println("; 자동 캡처 재트리거 최소 간격(ms, 500~60000). 기본 3000");
            pw.println("auto_capture_cooldown_ms=2000");
            pw.println();
            
            pw.println("; 카운트다운 길이(초, 1~10). 기본 3");
            pw.println("auto_capture_countdown_sec=1");
            pw.println();
            
            pw.println("; --- 보안 캡처 설정 (신규/중요) ---");
            pw.println("; true = 자동 보안 캡처 ON, false = OFF");
            pw.println("security_capture_enabled=true");
            pw.println();
            
            pw.println(";보안캡쳐 경로 설정(값 없을 경우 실행파일 위치에)");
            pw.println(";security_path=C:/MyFaceSec/Captures");
            pw.println();
            
            pw.println("; security_captures 폴더에 최대 몇 장까지 유지할지");
            pw.println("; 넘으면 오래된 파일부터 자동 삭제");
            pw.println("security_capture_max_files=1000");
            pw.println();
            
            pw.println("; 보안 캡처 이미지 포맷 (jpg 또는 png)");
            pw.println("security_capture_format=jpg");

            // (맨 아래에 있던 중복된 vmarg 설정들은 삭제했습니다.)

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // [readIniSection 메서드 보강]
    private Map<String, String> readIniSection(File f) {
        Map<String, String> m = new HashMap<>();
        try {
            // UTF-8이 안 되면 ANSI(MS949)로라도 읽어보도록 2차 시도 로직 추가 권장
            // (WinRun4J가 만든 파일은 시스템 인코딩일 확률이 높음)
            java.util.List<String> lines;
            try {
                lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                lines = Files.readAllLines(f.toPath(), java.nio.charset.Charset.defaultCharset());
            }

            for (String l : lines) {
                    if (l.trim().isEmpty() || l.startsWith(";") || l.startsWith("[")) {
                        continue;
                    }
                    String[] p = l.split("=", 2);
                    if (p.length > 1) {
                    	// ★★★ [수정] 키(key)를 무조건 소문자로 변환하여 저장 ★★★
                    	// 이렇게 하면 INI에 "MATCH_THRESHOLD"라고 써도 "match_threshold"로 읽힙니다.
                        m.put(p[0].trim().toLowerCase(), p[1].trim());
                    }
             }
        } catch (Exception e) {
            System.err.println("INI 읽기 실패: " + e.getMessage());
        }
        return m;
    }

    // [수정] 이름 변경: playWelcomeSound -> playEnrollGuideSound
    // 등록 화면에 진입할 때 안내 음성을 재생
    private void playEnrollGuideSound() {
        try {
        	File s = new File(BASE_PATH, "sounds/faceEnrollVoice.wav");
            
            if (s.exists()) {
                AudioInputStream ais = AudioSystem.getAudioInputStream(s);
                Clip c = AudioSystem.getClip();
                c.open(ais);
                c.start();
            }
        } catch (Exception e) {
        }
    }

    // [수정됨] 배경 이미지를 확대하지 않고 원본 크기로 그리는 패널
    class ImageBackgroundPanel extends JPanel {
    	private static final long serialVersionUID = 1L;
        private BufferedImage bgImage;
        public ImageBackgroundPanel(String imagePath) {

            super(new BorderLayout());
            try {
                File f = new File(imagePath);
                if (f.exists()) {
                    this.bgImage = ImageIO.read(f);
                } else {
                    //System.err.println("배경 이미지 없음: " + imagePath);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            // 이미지가 로드되었다면 패널 크기에 맞춰서 그리기
            if (bgImage != null) {
            	// [수정] 너비와 높이 인자를 뺐습니다.
            	// (0, 0) 좌표에 원본 해상도(1:1) 그대로 그립니다.
                g.drawImage(bgImage, 0, 0, this);
            }
        }
    }

    // ★★★ [수정됨] 윈도우 포커스 강제 탈취 (Alt 키 트릭) ★★★
    private void forceForeground() {
        long hwndID = Native.getComponentID(frame);
        
        // [수정] 객체(hwnd)가 아니라 ID값(hwndID)이 0인지 확인해야 함
        if (hwndID == 0) {
            return;
        }

        HWND hwnd = new HWND(new Pointer(hwndID));
        
        // 1. Alt 키를 누른 척 함 (윈도우가 '사용자 입력'으로 착각하게 만듦)
        User32.INSTANCE.keybd_event((byte) 0, (byte) 0, 0, 0);
        // 2. 포커스 요청
        User32.INSTANCE.SetForegroundWindow(hwnd);
        // 3. 최상단 고정 (TopMost)
        User32.INSTANCE.SetWindowPos(
                hwnd,
                HWND_TOPMOST,
                0, 0, 0, 0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW
        );
    }

    // [수정됨] 진짜 윈도우 핸들(ID)을 가져와서 강제로 박아버리는 코드
    // [수정됨] 보안 모드(탭 2)가 아닐 때만 최상단 고정
    private void startAlwaysOnTopWatchdog() {

        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                	// ★★★ [수정 핵심] "보안 모드(2번 탭)"가 아닐 때만 작동하도록 조건 추가 ★★★
                    if (tabbedPane != null && tabbedPane.getSelectedIndex() != 2) {
                    	// 1. 자바 Swing 기능
                        if (!frame.isAlwaysOnTop()) {
                        	frame.setAlwaysOnTop(true);
                        }
                        // 2. 윈도우 API (강력 방어)
                        if (frame.isVisible() && (frame.getExtendedState() != JFrame.ICONIFIED)) {
                            long hwndID = Native.getComponentID(frame);
                            HWND hwnd = new HWND(new Pointer(hwndID));
                            if (hwnd != null) {
                            	// 윈도우 포커스 락 해제 트릭
                                User32.INSTANCE.keybd_event((byte) 0, (byte) 0, 0, 0);
                                User32.INSTANCE.SetForegroundWindow(hwnd);
                                User32.INSTANCE.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
                            }
                        }
                    } else {
                    	// 보안 모드일 때는 '항상 위' 속성 해제 (다른 프로그램 사용 가능하게)
                    	frame.setAlwaysOnTop(false);
                    }
                    Thread.sleep(1000);
                } catch (Exception e) {
                	// 무시
                }
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
    }

    // [신규] "00105_01203" -> "105동 1203호" 로 변환하는 헬퍼 메서드
    private String getFriendlyName(String rawId) {
        if (rawId == null) {
            return "";
        }
        try {
        	// "_"가 포함된 형식이면 분리해서 변환 시도
            if (rawId.contains("_")) {
                String[] parts = rawId.split("_");
                if (parts.length == 2) {
                	// Integer.parseInt를 하면 "00105"가 숫자 105가 되면서 0이 사라짐
                    int dong = Integer.parseInt(parts[0]);
                    int ho = Integer.parseInt(parts[1]);
                    return dong + "동 " + ho + "호";
                }
            }
        } catch (Exception e) {
        	// 숫자가 아니거나 형식이 다르면 그냥 원래 문자열 반환 (예: "Admin")
        }
        return rawId; // 변환 실패 시 원본 반환
    }
    
 // =================================================================
    // ★★★ [신규] 가벼운 독립형 TCP 서버 (소켓 선발대) ★★★
    // =================================================================
    public static class SimpleTcpServer {
        private static ServerSocket serverSocket;
        private static boolean isRunning = false;
        private static FaceSecApp mainApp;
        private static final ExecutorService pool = Executors.newFixedThreadPool(5);

        // [변경] 스레드 시작 전에 bind부터 수행하여 포트 선점
        public static void start(int port) {
            try {
                // 1. 메인 스레드에서 즉시 포트 바인딩 (가장 중요)
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true); // 재시작 시 포트 점유 에러 방지
                serverSocket.bind(new java.net.InetSocketAddress(port), 50);
                
                isRunning = true;
                // System.out.println("[초고속] 소켓 포트(" + port + ") 개방 성공");

            } catch (IOException e) {
                // 포트를 못 열면 프로그램 실행 의미가 없으므로 에러 출력
                System.err.println("[치명적 오류] 포트 바인딩 실패: " + e.getMessage());
                return; 
            }
            
            // 2. 접속 대기(accept)는 별도 스레드에서 진행 (안 그러면 여기서 멈춤)
            Thread t = new Thread(() -> {
                try {
                    while (isRunning && !serverSocket.isClosed()) {
                        Socket client = serverSocket.accept(); // 여기서 대기
                        client.setSoTimeout(5000);
                        client.setTcpNoDelay(true);
                        pool.submit(() -> handleClient(client));
                    }
                } catch (IOException e) {
                    // 서버 종료 시 발생하는 에러는 무시
                }
            });
            t.setPriority(Thread.MAX_PRIORITY); // CPU 우선순위 높임
            t.start();
        }

        // 2. 앱(UI)이 준비되면 연결 (FaceSecApp 생성자 끝에서 호출)
        public static void setApp(FaceSecApp app) {
            mainApp = app;
            //System.out.println("[시스템] 소켓 서버와 UI 연결 완료");
        }

        public static void stop() {
            isRunning = false;
            pool.shutdownNow();
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
        }

        private static void handleClient(Socket client) {
            try (InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream()) {
                
                // 핸드쉐이크
                String handshake = readString(in);
                
                // [수정] 핸드쉐이크 실패 시 응답 없이 즉시 종료 (Silent Drop)
                if (handshake == null || !handshake.equals("raonraon")) {
                    return; // 여기서 함수가 끝나면 소켓도 자동으로 닫힘
                }
                
                // 성공 시에만 응답
                sendString(out, "raonnice");
                
                // =========================================================
                // ★★★ 진짜 웹뷰로 확인되었으니 타임아웃 해제 ★★★
                // =========================================================
                client.setSoTimeout(0);

                // 명령 루프
                while (isRunning) {
                    String cmd = readString(in);
                    if (cmd == null) break;
                    
                    if (!cmd.isEmpty()) {
                    	
                    	// ★★★ [수정 추가] 수신된 클라이언트 명령 로그 기록 ★★★
                        if (FaceSecApp.logger != null) {
                            FaceSecApp.logger.log("RECV", cmd);
                        }
                    	
                        // ★ 핵심: UI가 아직 준비 안 됐으면 '초기화 중' 응답
                        if (mainApp == null) {
                            //System.out.println("[TCP] 명령 수신했으나 UI 로딩 중: " + cmd);
                        	FaceSecApp.logger.log("TCP", "[TCP] 명령 수신했으나 UI 로딩 중: " + cmd);
                            sendString(out, "NK)System Initializing... Please Wait");
                        } else {
                            // UI가 준비되었으면 본체(FaceSecApp)로 명령 토스
                            mainApp.processCommand(cmd, out);
                        }
                    }
                }
            } catch (Exception e) {
                // e.printStackTrace();
            }
        }

        // 간단한 입출력 헬퍼
        private static void sendString(OutputStream out, String msg) throws IOException {
            out.write(msg.getBytes(StandardCharsets.UTF_8));
            out.write(0x00);
            out.flush();
        }

        private static String readString(InputStream in) throws IOException {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == 0x00) break;
                buffer.write(b);
            }
            if (buffer.size() == 0 && b == -1) return null;
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    // 클래스 멤버 변수 쪽에 추가 (전역 시간 측정용)
    public static long BOOT_START_TIME = 0;

    public static void main(String[] args) {
    	
        // 0. 로거 초기화 (가장 먼저 수행)
        logger = new LoggerService(BASE_PATH);
        logger.init();

    	// =============================================================
        // ★ [핵심] 비정상 종료(에러/OOM) 감지
        // =============================================================
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                // 1. 메모리 부족 (OOM) 감지
                if (e instanceof OutOfMemoryError) {
                    logger.log("FATAL", "========================================");
                    logger.log("FATAL", " [OOM] 메모리 부족으로 프로그램 사망");
                    logger.log("FATAL", "========================================");
                } 
                // 2. 일반 에러(NullPointer 등)로 인한 튕김 감지
                else {
                    logger.log("FATAL", "!!! 비정상 종료(CRASH) 감지 !!!");
                    logger.log("FATAL", "에러 내용: " + e.toString());
                    
                    // 어디서 에러 났는지 위치 기록
                    for (StackTraceElement elem : e.getStackTrace()) {
                        logger.log("FATAL", "    at " + elem.toString());
                    }
                }
            } catch (Throwable ex) {
                // 로그 남기다 에러나면 콘솔에라도 뿌림
                ex.printStackTrace();
            } finally {
                if (logger != null) {
                    logger.close();
                }
                // 로그 다 남겼으면 강제 종료 (좀비 프로세스 방지)
                System.exit(1);
            }
        });
        
        // 1. [측정 시작]
        BOOT_START_TIME = System.currentTimeMillis();
        // 아직 로그 시스템 로딩 전이므로 콘솔에 먼저 찍음
        //System.out.println("[PERF] Main Start: 0ms"); 

        // -------------------------------------------------------------
        // 1. 소켓 서버 시작
        // -------------------------------------------------------------
        int fastPort = 5001; 
        try {
            File[] candidates = { new File("../SETUP.INI"), new File("SETUP.INI") };
            for (File f : candidates) {
                if (f.exists()) {
                    java.util.List<String> lines = Files.readAllLines(f.toPath(), java.nio.charset.Charset.forName("MS949"));
                    for (String line : lines) {
                        if (line.trim().toLowerCase().startsWith("port=")) {
                            String[] parts = line.split("=", 2);
                            if (parts.length > 1) fastPort = Integer.parseInt(parts[1].trim());
                            break; 
                        }
                    }
                    break; 
                }
            }
        } catch (Exception e) {}

        SimpleTcpServer.start(fastPort);
        
        // ★ [로그] 소켓 오픈까지 걸린 시간
        long t1 = System.currentTimeMillis();
        logger.log("PERF", "Socket Opened (Port " + fastPort + "): " + (t1 - BOOT_START_TIME) + "ms");

        // -------------------------------------------------------------
        // 2. 스플래시 화면 (작업표시줄 아이콘)
        // -------------------------------------------------------------
        JFrame splash = new JFrame("Loading...");
        try {
            File iconFile = new File("images/icon.png");
            if (!iconFile.exists()) iconFile = new File("../images/icon.png");
            if (iconFile.exists()) {
                Image icon = Toolkit.getDefaultToolkit().getImage(iconFile.getAbsolutePath());
                splash.setIconImage(icon);
            }
        } catch (Exception e) {}
        
        splash.setUndecorated(true);
        splash.setBounds(-200, -200, 1, 1);
        splash.setVisible(true); 

        // ★ [로그] 아이콘 뜨는 데까지 걸린 시간
        long t2 = System.currentTimeMillis();
        logger.log("PERF", "Splash/Icon Visible: " + (t2 - BOOT_START_TIME) + "ms (Diff: " + (t2 - t1) + "ms)");

        // -------------------------------------------------------------
        // 3. JNA 로딩 (여기서 User32 로드됨)
        // -------------------------------------------------------------
        try {
            HWND hwnd = User32.INSTANCE.FindWindow(null, APP_TITLE);
            if (hwnd != null) {
                User32.INSTANCE.ShowWindow(hwnd, 9);
                User32.INSTANCE.SetForegroundWindow(hwnd);
                splash.dispose();
                SimpleTcpServer.stop();
                System.exit(0);
            }
        } catch (Exception e) { e.printStackTrace(); }

        // ★ [로그] JNA(User32) 로딩 시간 (이게 윈도우 캐시 없으면 느림)
        long t3 = System.currentTimeMillis();
        logger.log("PERF", "JNA Library Loaded: " + (t3 - BOOT_START_TIME) + "ms (Diff: " + (t3 - t2) + "ms)");

        // -------------------------------------------------------------
        // 4. 진짜 앱 생성 (안전장치 추가됨)
        // -------------------------------------------------------------
        SwingUtilities.invokeLater(() -> {
            try {
                long t4 = System.currentTimeMillis();
                logger.log("PERF", "Before FaceSecApp(): " + (t4 - BOOT_START_TIME) + "ms");
                
                FaceSecApp app = new FaceSecApp();
                SimpleTcpServer.setApp(app);
                
                // 스플래시 제거 (앱이 정상적으로 떴을 때만)
                if (splash != null) splash.dispose();
                
                long t5 = System.currentTimeMillis();
                logger.log("PERF", "App Visible & Ready: " + (t5 - BOOT_START_TIME) + "ms");
                
            } catch (Throwable e) {
                // ★ 여기가 없으면 에러 날 때 로그도 없이 그냥 죽습니다.
                logger.log("FATAL", "App Crash in Main: " + e.toString());
                e.printStackTrace(); // 콘솔에도 출력
                
                // 사용자에게 알림
                javax.swing.JOptionPane.showMessageDialog(null, 
                    "프로그램 실행 실패:\n" + e.getMessage(), 
                    "Critical Error", 
                    javax.swing.JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
    
}
