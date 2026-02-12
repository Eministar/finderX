package dev.eministar.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.filechooser.FileSystemView;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class SystemIconProvider {
    private static final String FOLDER_KEY = "__folder__";
    private static final String FILE_KEY = "__file__";

    private final FileSystemView fsView = FileSystemView.getFileSystemView();
    private final Map<String, Image> iconCache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "icon-loader");
        t.setDaemon(true);
        return t;
    });
    private final Image fallbackIcon = createFallbackIcon();

    public void loadIconAsync(Path path, boolean directory, Consumer<Image> callback) {
        String key = cacheKey(path, directory);
        Image cached = iconCache.get(key);
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        callback.accept(fallbackIcon);
        if (!inFlight.add(key)) {
            return;
        }
        iconExecutor.submit(() -> {
            try {
                Image loaded = loadSystemIcon(path, directory);
                iconCache.put(key, loaded);
                Platform.runLater(() -> callback.accept(loaded));
            } finally {
                inFlight.remove(key);
            }
        });
    }

    public void shutdown() {
        iconExecutor.shutdownNow();
    }

    private Image loadSystemIcon(Path path, boolean directory) {
        try {
            File file = path.toFile();
            Icon icon;
            if (file.exists()) {
                icon = fsView.getSystemIcon(file);
            } else if (directory) {
                icon = fsView.getSystemIcon(new File(System.getProperty("user.home")));
            } else {
                icon = fsView.getSystemIcon(new File(System.getenv("WINDIR"), "notepad.exe"));
            }
            return iconToImage(icon);
        } catch (Exception ignored) {
            return fallbackIcon;
        }
    }

    private static String cacheKey(Path path, boolean directory) {
        if (directory) {
            return FOLDER_KEY;
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        int idx = fileName.lastIndexOf('.');
        if (idx <= 0 || idx == fileName.length() - 1) {
            return FILE_KEY;
        }
        return fileName.substring(idx).toLowerCase();
    }

    private static Image iconToImage(Icon icon) {
        if (icon == null) {
            return createFallbackIcon();
        }
        if (icon instanceof ImageIcon imageIcon && imageIcon.getImage() != null) {
            BufferedImage buffered = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = buffered.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(imageIcon.getImage(), 0, 0, 16, 16, null);
            g.dispose();
            return SwingFXUtils.toFXImage(buffered, null);
        }
        BufferedImage bi = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        return SwingFXUtils.toFXImage(bi, null);
    }

    private static Image createFallbackIcon() {
        WritableImage image = new WritableImage(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int alpha = 255;
                int red = 90;
                int green = 130;
                int blue = 200;
                if (x < 2 || x > 13 || y < 2 || y > 13) {
                    red = 55;
                    green = 70;
                    blue = 110;
                }
                image.getPixelWriter().setArgb(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }
}
