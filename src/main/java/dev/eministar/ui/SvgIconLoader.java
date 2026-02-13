package dev.eministar.ui;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SvgIconLoader {
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private SvgIconLoader() {
    }

    public static Image load(String resourcePath, int width, int height) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        String key = resourcePath + "|" + width + "x" + height;
        return CACHE.computeIfAbsent(key, ignored -> renderSvg(resourcePath, width, height));
    }

    private static Image renderSvg(String resourcePath, int width, int height) {
        try (InputStream stream = SvgIconLoader.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return null;
            }
            BufferedImageTranscoder transcoder = new BufferedImageTranscoder();
            transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, (float) width);
            transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, (float) height);
            transcoder.transcode(new TranscoderInput(stream), null);
            BufferedImage image = transcoder.image;
            if (image == null) {
                return null;
            }
            return SwingFXUtils.toFXImage(image, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class BufferedImageTranscoder extends ImageTranscoder {
        private BufferedImage image;

        @Override
        public BufferedImage createImage(int width, int height) {
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        public void writeImage(BufferedImage image, TranscoderOutput transcoderOutput) {
            this.image = image;
        }
    }
}
