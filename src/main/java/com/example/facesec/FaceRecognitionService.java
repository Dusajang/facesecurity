package com.example.facesec;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_dnn.Net;
import org.bytedeco.opencv.opencv_objdetect.FaceRecognizerSF;

/**
 * 얼굴 인식 핵심 서비스 클래스
 * 역할: 모델 로드, 특징 추출(Vector), 유사도 비교, 데이터 캐시(파일/메모리) 관리
 */
public class FaceRecognitionService {
	
	private Net net;
    private LoggerService logger;
    
    // OpenCV의 얼굴 인식 AI 엔진 객체 (ONNX 모델 로드)
    private FaceRecognizerSF faceRecognizer;
    
    // [메모리 DB] 실행 중 빠른 검색을 위해 RAM에 데이터를 저장함
    // Key: 사용자ID(폴더명), Value: 해당 사용자의 얼굴 특징 벡터 목록(여러 장)
    private Map<String, ArrayList<float[]>> userVectors = new HashMap<>();
    
    // 인식 성공 기준 유사도 (기본 0.75, 이 값보다 높아야 본인으로 인정)
    private double cosineThreshold = 0.75;
    
    // 각 사용자 폴더에 생성될 캐시 파일의 이름
    private static final String CACHE_FILE_NAME = "vectors_cache.dat";

    // 기본 생성자
    public FaceRecognitionService() { }

    public void setLogger(LoggerService logger) {
        this.logger = logger;
    }

    /**
     * 1. ONNX 모델 파일 로드 (수정됨)
     * - FaceRecognizerSF 대신 Net 객체를 초기화해야 합니다.
     */
    public boolean loadModel(String modelPath) {
        try {
            File f = new File(modelPath);
            if (!f.exists()) {
                System.err.println("[오류] 모델 파일이 없습니다: " + modelPath);
                if (logger != null) logger.log("ER", "[오류] 모델 파일이 없습니다: " + modelPath);
                return false;
            }
            
            // =========================================================
            // ★★★ [핵심 수정] Net 객체 초기화 ★★★
            // 기존 코드: faceRecognizer = FaceRecognizerSF.create(...); (X)
            // 변경 코드: this.net = readNetFromONNX(...); (O)
            // =========================================================
            this.net = org.bytedeco.opencv.global.opencv_dnn.readNetFromONNX(f.getAbsolutePath());
            
            if (this.net.empty()) {
                System.err.println("[오류] 모델 로드 실패 (Net is empty)");
                if (logger != null) logger.log("ER", "[오류] 모델 로드 실패 (Net is empty)");
                return false;
            }
            
            //System.out.println("[시스템] ArcFace 모델 로드 성공 (Net 초기화 완료)");
            if (logger != null) logger.log("AI", "[시스템] ArcFace 모델 로드 성공 (Net 초기화 완료)");
            return true;
        } catch (Exception e) {
            System.err.println("[오류] loadModel 중 예외 발생");
            if (logger != null) logger.log("ER", "[오류] loadModel 중 예외 발생");
            e.printStackTrace();
            return false;
        }
    }
    
    // 벡터 정규화 (길이를 1.0으로 맞춤)
    private void normalize(float[] v) {
        double sum = 0;
        for (float val : v) sum += val * val;
        double norm = Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) v[i] /= norm;
        }
    }

    /**
     * 매칭 임계값 설정 (INI 파일 등에서 읽어온 값 적용)
     * @param val 설정할 임계값 (예: 0.75)
     */
    public void setThreshold(double val) {
        this.cosineThreshold = val;
    }

 // ArcFace ResNet100 전용 전처리 및 특징 추출 (메모리 누수 수정됨)
    public synchronized float[] extractFeature(Mat faceImage) {
        if (this.net == null || this.net.empty()) {
            // ... (에러 로그 생략)
            return null;
        }

        // ★★★ [수정] try-with-resources로 네이티브 메모리 자동 해제 보장 ★★★
        // 이 블록을 빠져나가면 blob, output, flat은 무조건 C++ 메모리에서 삭제됩니다.
        try (
            // 1. Blob 생성 (전처리)
            Mat blob = org.bytedeco.opencv.global.opencv_dnn.blobFromImage(
                    faceImage, 
                    1.0, 
                    new Size(112, 112), 
                    new Scalar(0, 0, 0, 0), 
                    true, 
                    false, 
                    CV_32F
            )
        ) {
            if (blob.empty()) return null;

            // 2. 모델 주입
            this.net.setInput(blob);
            
            // 3. 추론 및 결과 변환 (여기서 생성되는 Mat들도 try 구문 안에서 관리)
            try (Mat output = this.net.forward()) {
                
                if (output.empty()) return null;

                try (Mat flat = output.reshape(1, 1)) {
                    int cols = flat.cols();
                    float[] vector = new float[cols];
                    
                    // 데이터 복사 (C++ -> Java)
                    // FloatPointer는 가벼운 포인터라 GC가 처리하지만, 
                    // 확실하게 하기 위해 close() 하거나 범위를 좁히는 게 좋음
                    try (org.bytedeco.javacpp.FloatPointer ptr = new org.bytedeco.javacpp.FloatPointer(flat.data())) {
                        ptr.get(vector);
                    }

                    // 4. 정규화
                    normalize(vector);
                    
                    return vector;
                }
            }
        } catch (Exception e) {
            System.err.println("[오류] extractFeature 처리 중: " + e.getMessage());
            return null;
        }
    }

    /**
     * 3. 얼굴 식별 (1:N 매칭) - Max Score & 다중 매칭 방지 방식
     * (동일인이 여러 세대에 등록된 경우 보안을 위해 판독 불가 처리)
     */
    public synchronized String identify(float[] targetVector) {
        String bestMatchName = null;
        double globalMaxScore = 0.0;
        double secondMaxScore = 0.0;

        // ★ [신규 추가] 임계값(Threshold)을 넘은 "합격 세대"의 개수를 셈
        int passCount = 0; 
        String lastPassedName = null;

        // 1. 모든 사용자 폴더 검사
        for (Map.Entry<String, ArrayList<float[]>> entry : userVectors.entrySet()) {
            String name = entry.getKey();
            ArrayList<float[]> vectors = entry.getValue();
            
            if (vectors == null || vectors.isEmpty()) continue;

            double localMaxScore = 0.0; 

            // 이 세대(name) 안에서 가장 높은 점수 찾기
            for (int i = 0; i < vectors.size(); i++) {
                float[] dbVector = vectors.get(i);
                double score = cosineSimilarity(targetVector, dbVector);

                if (score > localMaxScore) {
                    localMaxScore = score;
                }
            }

            // ★ [신규 추가] 이 세대의 최고 점수가 합격선(Threshold)을 넘었나?
            if (localMaxScore >= cosineThreshold) {
                passCount++;
                lastPassedName = name;
            }

            // 2. 전체 1등과 2등 갱신
            if (localMaxScore > globalMaxScore) {
                secondMaxScore = globalMaxScore; 
                globalMaxScore = localMaxScore;  
                bestMatchName = name;
            } else if (localMaxScore > secondMaxScore) {
                secondMaxScore = localMaxScore; 
            }
        }

        // ==========================================================
        // 3. [최종 판정 및 보안 검사]
        // ==========================================================

        if (globalMaxScore >= cosineThreshold) {
            
            // [보안 1] 합격선(Threshold)을 넘은 세대가 2개 이상인가? (중복 등록 확인)
            if (passCount >= 2) {
                if (logger != null) {
                    logger.log("WARN", String.format("[보안 경고] 중복 사용자 감지 - 합격 기준을 넘은 세대가 %d개 있음 (예: %s, %s 등). 판독 불가 처리.", 
                                        passCount, bestMatchName, (bestMatchName.equals(lastPassedName) ? "다른 세대" : lastPassedName)));
                }
                return "!AMBIGUOUS";
            }

            // [보안 2] 격차(Gap) 검사 (기존 유지, 0.05 미만 차이면 판독 불가)
            double gap = globalMaxScore - secondMaxScore;
            if (gap < 0.05) { 
                if (logger != null) {
                    logger.log("WARN", String.format("[보안 경고] 판독 불가 - 1등(%s, %.3f) vs 2등(%.3f) 차이 미세 (격차: %.3f)", 
                                        bestMatchName, globalMaxScore, secondMaxScore, gap));
                }
                return "!AMBIGUOUS"; 
            }

            // 모든 보안 검사 통과 시
            //System.out.printf("[인식성공] %s | 점수:%.4f (Max)\n", bestMatchName, globalMaxScore);
            return bestMatchName;
        }
        
        return null; // 누구와도 일치하지 않음
    }

    /**
     * 4. 프로그램 시작 시 모든 사용자 데이터 로드
     * - 각 폴더를 돌며 캐시 파일(vectors_cache.dat) 확인
     * - [보안] 사진이 없으면 캐시도 삭제 (좀비 데이터 방지)
     * - [동기화] 사진 수와 캐시 수가 다르면 재스캔
     */
    public int loadAllUsers(File rootDir) {
        userVectors.clear(); // 기존 메모리 초기화
        
        if (!rootDir.exists()) return 0;
        File[] dirs = rootDir.listFiles(File::isDirectory); // 폴더 목록
        if (dirs == null) return 0;

        for (File userDir : dirs) {
            String userId = userDir.getName();
            File cacheFile = new File(userDir, CACHE_FILE_NAME);
            
            // 1. 해당 폴더의 실제 JPG 파일 개수 확인
            File[] jpgs = userDir.listFiles((d, name) -> name.toLowerCase().endsWith(".jpg"));
            int jpgCount = (jpgs != null) ? jpgs.length : 0;

            // =========================================================
            // ★ [보안 패치] 사진이 하나도 없으면 캐시 삭제 후 건너뜀
            // =========================================================
            if (jpgCount == 0) {
                if (cacheFile.exists()) {
                    cacheFile.delete(); // 좀비 캐시 파일 삭제
                    //System.out.println("[청소] " + userId + ": 사진 없음 -> 캐시 삭제 및 로드 생략");
                }
                continue; // 메모리에 넣지 않고 다음 폴더로 이동
            }

            // 2. 캐시 파일 로드 시도
            ArrayList<float[]> loadedVectors = null;

            if (cacheFile.exists()) {
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cacheFile))) {
                    // 캐시 파일 읽기
                    loadedVectors = (ArrayList<float[]>) ois.readObject();
                    
                    // ★ [동기화 검사] 캐시 데이터 수 != 실제 파일 수 -> 변경 감지
                    if (loadedVectors.size() != jpgCount) {
                        //System.out.println("[동기화] " + userId + ": 파일 변경 감지(" + loadedVectors.size() + "->" + jpgCount + ") -> 재스캔");
                        loadedVectors = null; // 폐기 (아래에서 다시 만듦)
                    }
                } catch (Exception e) {
                    //System.out.println("[오류] " + userId + ": 캐시 손상됨 -> 재스캔");
                    loadedVectors = null;
                }
            }

            // 3. 캐시가 없거나 폐기되었다면(null) -> 실제 이미지 재분석(Re-scan)
            if (loadedVectors == null) {
                loadedVectors = new ArrayList<>();
                
                if (jpgs != null) {
                    for (File imgFile : jpgs) {
                        // ★ [수정] imread로 읽은 이미지는 반드시 release 해야 함!
                        // Mat 객체는 try-with-resources로 감싸서 자동 해제
                        try (Mat img = imread(imgFile.getAbsolutePath())) {
                            if (img != null && !img.empty()) {
                                float[] vec = extractFeature(img); // 특징 추출
                                if (vec != null) loadedVectors.add(vec);
                            }
                        } // <--- 여기서 img.release() 자동 실행됨
                    }
                }
                
                // 특징점이 정상적으로 추출되었을 때만 캐시 저장
                if (!loadedVectors.isEmpty()) {
                    saveUserCache(userDir, loadedVectors);
                }
            }

            // 4. 최종 데이터를 메모리에 등록 (데이터가 있을 때만)
            if (!loadedVectors.isEmpty()) {
                userVectors.put(userId, loadedVectors);
            }
        }

        // 전체 로딩된 벡터(장수) 총합 반환
        int total = 0;
        for(ArrayList<float[]> list : userVectors.values()) total += list.size();
        
        //System.out.println("[시스템] 총 " + userVectors.size() + "명(" + total + "장) 로드 완료.");
        return total;
    }
    
    // [보조 메서드] 폴더 안에 jpg 파일이 있는지 확인
    private boolean hasImageFiles(File dir) {
        if (!dir.isDirectory()) return false;
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jpg"));
        return files != null && files.length > 0;
    }

    /**
     * [Helper] 특정 폴더에 벡터 리스트를 캐시 파일로 저장하는 함수
     */
    public void saveUserCache(File userDir, ArrayList<float[]> vectors) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File(userDir, CACHE_FILE_NAME)))) {
            oos.writeObject(vectors);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    // [추가] 특정 사용자의 현재 메모리 상태를 즉시 캐시 파일로 저장
    public void saveUserCacheImmediate(String userId) {
        // 1. 메모리에서 데이터 가져오기
        ArrayList<float[]> vectors = userVectors.get(userId);
        if (vectors == null) return;

        // 2. 해당 사용자의 폴더 경로 찾기 (datasetRoot가 필요함)
        // (주의: FaceRecognitionService가 datasetRoot 경로를 멤버변수로 가지고 있어야 합니다.
        //  없다면 인자로 File datasetRoot를 받아야 합니다.)
        
        // 간단한 방법: FaceSecApp에서 호출할 때 root 경로를 넘겨받는 방식
        // 아래 코드로 사용하세요.
    }

    // [수정된 버전] 인자로 루트 폴더를 받아서 저장 수행
    public void saveUserCacheImmediate(File rootDir, String userId) {
        ArrayList<float[]> vectors = userVectors.get(userId);
        if (vectors == null) return;

        File userDir = new File(rootDir, userId);
        if (!userDir.exists()) userDir.mkdirs();

        // 기존에 있던 saveUserCache 메서드 재사용
        saveUserCache(userDir, vectors); 
        //System.out.println("[시스템] " + userId + " 벡터 캐시 파일 즉시 업데이트 완료.");
    }

    /**
     * 5. 신규 얼굴 등록 (메모리 + 폴더별 캐시 갱신)
     * @param userId 사용자 ID
     * @param vector 추출된 특징 벡터
     */
    public synchronized void registerUserInMemory(String userId, float[] vector) {
        // 메모리에 추가
        userVectors.computeIfAbsent(userId, k -> new ArrayList<>()).add(vector);
        
        // 해당 폴더의 캐시 파일도 즉시 갱신 (껐다 켜도 유지되도록)
        File userDir = new File("dataset", userId); 
        if (userDir.exists()) {
            saveUserCache(userDir, userVectors.get(userId));
        }
    }
    
    /**
     * 6. 사용자 삭제 (메모리에서만 삭제, 파일 삭제는 FaceSecApp에서 수행됨)
     * @param userId 삭제할 사용자 ID
     * @return 삭제 성공 여부
     */
    public synchronized boolean removeUserFromMemory(String userId) {
        if (userVectors.containsKey(userId)) {
            userVectors.remove(userId);
            // 참고: 실제 파일(JPG, dat) 삭제는 FaceSecApp 클래스의 deleteUnitData 메서드가 담당함
            return true;
        }
        return false;
    }
    
    // 특정 사용자 폴더를 다시 스캔하여 메모리 갱신
    public synchronized void reloadUser(String userId) {
        File userDir = new File("dataset", userId);
        if (!userDir.exists()) {
            userVectors.remove(userId);
            return;
        }
        
        // 1. 메모리 비우기
        userVectors.remove(userId);
        
        // 2. 디스크에서 다시 읽기
        ArrayList<float[]> vectors = new ArrayList<>();
        File[] images = userDir.listFiles((d, name) -> name.toLowerCase().endsWith(".jpg"));
        
        if (images != null) {
            Arrays.sort(images, Comparator.comparing(File::getName));
            
            for (File imgFile : images) {
                // ★ [수정] 여기서도 Mat 해제 필수
                try (Mat img = imread(imgFile.getAbsolutePath())) {
                    if (img != null && !img.empty()) {
                        float[] vec = extractFeature(img);
                        if (vec != null) vectors.add(vec);
                    }
                }
            }
        }
        
        // 3. 갱신 및 캐시 저장
        if (!vectors.isEmpty()) {
            userVectors.put(userId, vectors);
            saveUserCache(userDir, vectors);
        }
        System.out.println("[시스템] 데이터 갱신 완료: " + userId);
        if (logger != null) logger.log("SYS", "[시스템] 데이터 갱신 완료: " + userId);
    }

    /**
     * [Helper] 두 벡터 간의 코사인 유사도 계산 (1.0에 가까울수록 유사함)
     */
    private double cosineSimilarity(float[] v1, float[] v2) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        // 내적 / (크기 * 크기)
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    // =================================================================
    // [필수] 수동 등록 시 50장이 꽉 차면, 메모리에서 가장 오래된 것 삭제
    // =================================================================
    public synchronized void removeOldestVector(String userId) {
        if (userVectors.containsKey(userId)) {
            ArrayList<float[]> list = userVectors.get(userId);
            
            if (list != null && !list.isEmpty()) {
                // 1. 메모리 리스트에서 0번(가장 오래된 것) 삭제
                list.remove(0); 
                
                // 2. 변경된 리스트를 캐시 파일에 덮어쓰기 (동기화)
                File userDir = new File("dataset", userId);
                if (userDir.exists()) {
                    saveUserCache(userDir, list);
                }
                // System.out.println("   └─ [메모리 정리] " + userId + "의 오래된 벡터 삭제 완료");
            }
        }
    }
}