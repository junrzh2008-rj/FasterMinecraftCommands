import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Generates the FMCC mod icon: white background, black "FMCC" wordmark. */
public final class GenIcon {
    public static void main(String[] args) throws Exception {
        int size = 128;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // Pure white background.
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);

        // Thin black frame keeps the icon readable in dark mod lists.
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(3f));
        g.drawRect(2, 2, size - 5, size - 5);

        // Fit "FMCC" inside the square.
        String text = "FMCC";
        int targetWidth = size - 22;
        Font base = new Font("Arial", Font.BOLD, 100);
        FontMetrics fm = g.getFontMetrics(base);
        double scale = targetWidth / (double) fm.stringWidth(text);
        int fontSize = (int) Math.floor(100 * scale);
        Font font = base.deriveFont((float) fontSize);
        g.setFont(font);
        fm = g.getFontMetrics(font);

        int textWidth = fm.stringWidth(text);
        int x = (size - textWidth) / 2;
        int y = (size - fm.getHeight()) / 2 + fm.getAscent();
        g.setColor(Color.BLACK);
        g.drawString(text, x, y);

        g.dispose();
        File out = new File(args.length > 0 ? args[0]
                : "D:/DeepSeek-Harness/FMCC/src/main/resources/assets/fmcc/icon.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
    }
}
