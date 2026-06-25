package com.example.facesec;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * FaceSecApp TCP 테스트 클라이언트 (Protocol V2.4)
 * - Raw String + 0x00 Null Terminator
 * - 3단 응답 구조 (OK -> SC -> MV) 지원 (MV 제외됨)
 * - IP 및 포트 동적 입력 지원
 */
public class TcpTestClient {

    private static String serverHost = "127.0.0.1"; 
    private static int serverPort = 5001;
    private static final String CONFIG_FILE = "face.ini";

    public static void main(String[] args) {
        // 1. ini 파일이 있다면 먼저 기본값으로 로드
        loadConfig();

        Scanner scanner = new Scanner(System.in);

        System.out.println("===========================================");
        System.out.println("  FaceSecApp TCP Client (Protocol V2.4)");
        System.out.println("  [가변 길이 문자열 + 0x00 종결자 방식]");
        System.out.println("===========================================");
        
        // 2. IP 입력 받기 (엔터 치면 기본값 사용)
        System.out.print("접속할 서버 IP를 입력하세요 (엔터 시 기본값 " + serverHost + "): ");
        String inputIp = scanner.nextLine().trim();
        if (!inputIp.isEmpty()) {
            serverHost = inputIp;
        }

        // 3. 포트 입력 받기 (엔터 치면 기본값 사용)
        System.out.print("접속할 서버 포트를 입력하세요 (엔터 시 기본값 " + serverPort + "): ");
        String inputPort = scanner.nextLine().trim();
        if (!inputPort.isEmpty()) {
            try {
                serverPort = Integer.parseInt(inputPort);
            } catch (NumberFormatException e) {
                System.out.println("[경고] 잘못된 숫자 형식입니다. 기본 포트(" + serverPort + ")를 사용합니다.");
            }
        }

        System.out.println("\n서버 타겟 설정됨: " + serverHost + ":" + serverPort);

        // 4. 초기 연결 테스트
        if (!testInitialConnection()) {
            System.err.println("[경고] 서버 연결 또는 인증 실패. (서버가 켜져 있는지 확인하세요)");
        } else {
            System.out.println("[시스템] 서버 연결 성공!");
        }

        System.out.println("\n[명령어 예시]");
        System.out.println(" 1. REG:00101_01001  (등록)");
        System.out.println(" 2. REC              (인식)");
        System.out.println(" 3. DEL:00101_01001  (삭제)");
        System.out.println(" 4. SHOW=1 / SHOW=0  (창제어)");
        System.out.println(" 5. EXIT             (종료)");

        // 5. 명령어 입력 루프
        while (true) {
            System.out.print("\n명령어 입력> ");
            String command = scanner.nextLine().trim();

            if (command.isEmpty()) continue;
            if (command.equalsIgnoreCase("EXIT")) break;

            sendCommandAndReceive(command);
        }
        scanner.close();
    }

    // =========================================================================
    // [수정됨] 2단계 응답 처리 로직 (OK -> 결과)
    // =========================================================================
    private static void sendCommandAndReceive(String command) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(serverHost, serverPort), 3000);
            socket.setSoTimeout(35000); // 35초 대기

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // 1. 핸드쉐이크
            sendPacket(out, "raonraon");
            String handshakeRes = readPacket(in);
            
            if (!"raonnice".equals(handshakeRes)) {
                System.err.println("[인증 실패] 수신된 값: " + handshakeRes);
                return;
            }

            // 2. 명령어 전송
            sendPacket(out, command);

            // 3. 응답 수신
            // ----------------------------------------------------
            // [1차 응답] OK 확인
            String res1 = readPacket(in);
            System.out.println("[1차 응답] " + res1);

            if (res1 == null || !res1.startsWith("OK)")) {
                // OK가 아니거나(바로 SC/FA가 온 경우 등), EXIT_OK 같은 단발성 명령인 경우 종료
                return; 
            }

            System.out.println("... 작업 수행 중 (결과 대기) ...");

            // [2차 응답] 최종 결과 (SC 또는 FA 또는 ER)
            String res2 = readPacket(in);
            System.out.println("[2차 응답] " + res2);

        } catch (SocketTimeoutException e) {
            System.err.println("[오류] 응답 시간 초과 (Timeout)");
        } catch (IOException e) {
            System.err.println("[오류] 통신 에러: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception e) {}
        }
    }

    // ===============================================================
    // ★★★ [수정됨] Raw String + 0x00 종결자 방식 ★★★
    // ===============================================================

    // [송신] 문자열 바이트 + 0x00 전송
    private static void sendPacket(OutputStream out, String msg) throws IOException {
        if (msg == null) msg = "";
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        out.write(data);
        out.write(0x00); // Null Terminator
        out.flush();
    }

    // [수신] 0x00이 나올 때까지 읽기
    private static String readPacket(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == 0x00) break; // 끝 표시
            buffer.write(b);
        }
        if (buffer.size() == 0 && b == -1) return null; // 연결 끊김
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    // [설정 로드 등 기존 코드 유지]
    private static void loadConfig() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.toLowerCase().startsWith("port=")) {
                    try { serverPort = Integer.parseInt(line.split("=")[1].trim()); } catch (Exception e) {}
                }
            }
        } catch (IOException e) { }
    }
    
    private static boolean testInitialConnection() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serverHost, serverPort), 2000);
            return true;
        } catch (Exception e) { return false; }
    }
}