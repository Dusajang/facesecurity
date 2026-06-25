// File: src/main/java/com/example/facesec/SoundUtils.java
package com.example.facesec;

import javax.sound.sampled.*;
import java.io.File;

public final class SoundUtils {
    private SoundUtils() {}

    /** WAV(PCM 권장) 파일을 비동기로 1회 재생 */
    public static void playAsync(final File wavFile) {
        if (wavFile == null || !wavFile.exists()) return;

        Thread t = new Thread(new Runnable() {
            public void run() {
                AudioInputStream ais = null;
                Clip clip = null;
                try {
                    ais = AudioSystem.getAudioInputStream(wavFile);
                    DataLine.Info info = new DataLine.Info(Clip.class, ais.getFormat());
                    clip = (Clip) AudioSystem.getLine(info);

                    // 재생 종료 시 리소스 닫기
                    final Clip clipRef = clip;
                    clip.addLineListener(new LineListener() {
                        public void update(LineEvent event) {
                            if (event.getType() == LineEvent.Type.STOP || event.getType() == LineEvent.Type.CLOSE) {
                                try { clipRef.stop(); } catch (Exception ignored) {}
                                try { clipRef.close(); } catch (Exception ignored) {}
                            }
                        }
                    });

                    clip.open(ais);
                    clip.start();

                    // 끝까지 기다렸다가 자연종료(다른 스레드이므로 UI 블로킹 없음)
                    while (clip.isRunning()) {
                        try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    }
                } catch (Exception ignore) {
                    // 필요 시 로깅/토스트 처리 가능
                } finally {
                    try { if (ais != null) ais.close(); } catch (Exception ignored) {}
                    try { if (clip != null && clip.isOpen()) clip.close(); } catch (Exception ignored) {}
                }
            }
        }, "WavPlayThread");
        t.setDaemon(true);
        t.start();
    }
}
