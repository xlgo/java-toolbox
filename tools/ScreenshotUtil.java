import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class ScreenshotUtil {
    public static void main(String[] args) throws Exception {
        Thread.sleep(1500);

        Rectangle bounds = null;
        for (int i = 0; i < 30; i++) {
            Frame[] frames = Frame.getFrames();
            for (Frame f : frames) {
                if (f.isShowing() && f.getTitle() != null && f.getTitle().contains("Java")) {
                    bounds = f.getBounds();
                    break;
                }
            }
            if (bounds != null) break;
            Thread.sleep(200);
        }

        if (bounds == null) {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            bounds = ge.getMaximumWindowBounds();
        }

        Robot robot = new Robot();
        BufferedImage img = robot.createScreenCapture(bounds);
        String path = args.length > 0 ? args[0] : "screenshots/overview.png";
        ImageIO.write(img, "png", new File(path));
        System.out.println("Saved: " + new File(path).getAbsolutePath());
    }
}
