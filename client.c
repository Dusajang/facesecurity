#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <winsock2.h>
#include <ws2tcpip.h>

// 윈도우 소켓 라이브러리 링크 (명령어에서 -lws2_32 옵션으로 처리)

#define DEFAULT_IP "127.0.0.1"
#define DEFAULT_PORT 5001
#define MAX_BUFFER 1024

// 문자열 + 0x00(Null) 전송 함수
void send_packet(SOCKET sock, const char* msg) {
    if (msg == NULL) msg = "";
    send(sock, msg, strlen(msg), 0);
    
    // 0x00 종결자 1바이트 전송
    char terminator = 0x00;
    send(sock, &terminator, 1, 0);
}

// 0x00이 나올 때까지 1바이트씩 읽는 함수
// 반환값: 1(성공), 0(연결끊김), -1(에러), -2(타임아웃)
int read_packet(SOCKET sock, char* buffer) {
    int i = 0;
    char c;
    while (i < MAX_BUFFER - 1) {
        int bytes_received = recv(sock, &c, 1, 0);
        if (bytes_received > 0) {
            if (c == 0x00) {
                break; // 종결자 확인 시 종료
            }
            buffer[i++] = c;
        } else if (bytes_received == 0) {
            return 0; // 연결 끊김
        } else {
            int err = WSAGetLastError();
            if (err == WSAETIMEDOUT) {
                return -2; // 타임아웃
            }
            return -1; // 기타 에러
        }
    }
    buffer[i] = '\0'; // C언어 문자열 끝처리
    return 1;
}

// 명령어 전송 및 2단 응답 수신 로직
void send_command_and_receive(const char* ip, int port, const char* command) {
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        printf("[오류] 소켓 생성 실패\n");
        return;
    }

    struct sockaddr_in server_addr;
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(port);
    server_addr.sin_addr.s_addr = inet_addr(ip);

    // 타임아웃 설정 (35초)
    DWORD timeout = 35000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (const char*)&timeout, sizeof(timeout));

    if (connect(sock, (struct sockaddr*)&server_addr, sizeof(server_addr)) == SOCKET_ERROR) {
        printf("[오류] 서버 연결 실패\n");
        closesocket(sock);
        return;
    }

    char res_buf[MAX_BUFFER];

    // 1. 핸드쉐이크
    send_packet(sock, "raonraon");
    int r = read_packet(sock, res_buf);
    
    if (r != 1 || strcmp(res_buf, "raonnice") != 0) {
        printf("[인증 실패] 수신된 값: %s\n", (r == 1) ? res_buf : "Error/Timeout");
        closesocket(sock);
        return;
    }

    // 2. 명령어 전송
    send_packet(sock, command);

    // 3. 1차 응답 (OK) 수신
    r = read_packet(sock, res_buf);
    if (r == -2) {
        printf("[오류] 응답 시간 초과 (Timeout)\n");
        closesocket(sock);
        return;
    } else if (r <= 0) {
        printf("[오류] 통신 에러 또는 연결 끊김\n");
        closesocket(sock);
        return;
    }

    printf("[1차 응답] %s\n", res_buf);

    // OK) 로 시작하지 않으면 단발성 명령이므로 종료
    if (strncmp(res_buf, "OK)", 3) != 0) {
        closesocket(sock);
        return;
    }

    printf("... 작업 수행 중 (결과 대기) ...\n");

    // 4. 2차 응답 (최종 결과) 수신
    r = read_packet(sock, res_buf);
    if (r == 1) {
        printf("[2차 응답] %s\n", res_buf);
    } else if (r == -2) {
        printf("[오류] 응답 시간 초과 (Timeout)\n");
    } else {
        printf("[오류] 통신 에러 또는 연결 끊김\n");
    }

    closesocket(sock);
}

// 띄어쓰기(엔터) 제거 유틸 함수
void trim_newline(char* str) {
    str[strcspn(str, "\r\n")] = 0;
}

int main() {
    // ★★★ 까만 창(콘솔)을 강제로 UTF-8 모드로 바꾸는 마법의 2줄 ★★★
    SetConsoleOutputCP(65001); 
    SetConsoleCP(65001);

    // 윈도우 소켓 초기화 (C언어 윈도우 통신 필수작업)
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        printf("Winsock 초기화 실패\n");
        return 1;
    }

    char server_ip[100] = DEFAULT_IP;
    int server_port = DEFAULT_PORT;
    char input_buf[256];

    printf("===========================================\n");
    printf("  FaceSecApp TCP Client (C Native Version)\n");
    printf("  [가변 길이 문자열 + 0x00 종결자 방식]\n");
    printf("===========================================\n");

    // IP 입력
    printf("접속할 서버 IP를 입력하세요 (엔터 시 기본값 %s): ", DEFAULT_IP);
    fgets(input_buf, sizeof(input_buf), stdin);
    trim_newline(input_buf);
    if (strlen(input_buf) > 0) {
        strcpy(server_ip, input_buf);
    }

    // 포트 입력
    printf("접속할 서버 포트를 입력하세요 (엔터 시 기본값 %d): ", DEFAULT_PORT);
    fgets(input_buf, sizeof(input_buf), stdin);
    trim_newline(input_buf);
    if (strlen(input_buf) > 0) {
        server_port = atoi(input_buf);
    }

    printf("\n서버 타겟 설정됨: %s:%d\n", server_ip, server_port);

    printf("\n[명령어 예시]\n");
    printf(" 1. REG:00101_01001  (등록)\n");
    printf(" 2. REC              (인식)\n");
    printf(" 3. DEL:00101_01001  (삭제)\n");
    printf(" 4. SHOW=1 / SHOW=0  (창제어)\n");
    printf(" 5. EXIT             (종료)\n");

    while (1) {
        printf("\n명령어 입력> ");
        fgets(input_buf, sizeof(input_buf), stdin);
        trim_newline(input_buf);

        if (strlen(input_buf) == 0) continue;
        if (strcmp(input_buf, "EXIT") == 0 || strcmp(input_buf, "exit") == 0) {
            break;
        }

        send_command_and_receive(server_ip, server_port, input_buf);
    }

    // 윈도우 소켓 자원 해제
    WSACleanup();
    return 0;
}