package com.example.facesec;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.global.opencv_imgproc;

public final class CvUtils {
    private CvUtils() {}

    public static BufferedImage matToBufferedImage(Mat src) {
        if (src == null || src.empty()) return null;

        Mat mat = src;

        // 4채널(BGRA) → 3채널(BGR)로 변환
        if (mat.channels() == 4) {
            Mat bgr = new Mat();
            opencv_imgproc.cvtColor(mat, bgr, opencv_imgproc.COLOR_BGRA2BGR);
            mat = bgr;
        }

        BufferedImage image;

        if (mat.channels() == 3) {
            // ★ BGR Mat → TYPE_3BYTE_BGR (변환 없이 그대로 복사)
            int width = mat.cols();
            int height = mat.rows();
            int channels = 3;
            byte[] source = new byte[width * height * channels];
            mat.data().get(source);

            image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            System.arraycopy(source, 0, target, 0, source.length);

        } else if (mat.channels() == 1) {
            // 그레이 Mat → TYPE_BYTE_GRAY
            int width = mat.cols();
            int height = mat.rows();
            byte[] source = new byte[width * height];
            mat.data().get(source);

            image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            System.arraycopy(source, 0, target, 0, source.length);

        } else {
            // 기타 채널 수는 안전하게 BGR로 변환 후 재귀 처리
            Mat bgr = new Mat();
            opencv_imgproc.cvtColor(mat, bgr, opencv_imgproc.COLOR_RGB2BGR);
            return matToBufferedImage(bgr);
        }
        return image;
    }
}
