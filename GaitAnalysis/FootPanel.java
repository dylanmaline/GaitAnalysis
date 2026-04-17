import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;

public class FootPanel extends JPanel {
    private int[] fsr;
    private int gaitLateral;   // -1 left, 0 normal, +1 right
    private int gaitSagittal;  // -1 backward, 0 normal, +1 forward
    private boolean showHeatmap = false;
    private BufferedImage footOutline;
    private Point[] sensorPoints = new Point[5];

    public FootPanel(int[] fsr, int gaitLateral, int gaitSagittal, int[] avgPressure) {
        this.fsr          = fsr;
        this.gaitLateral  = gaitLateral;
        this.gaitSagittal = gaitSagittal;
        loadFootImage();
    }

    private void loadFootImage() {
        try {
            footOutline = ImageIO.read(new File("FootAppDrawing.png"));
            scanForSensors();
        } catch (IOException e) {
            System.err.println("Error: Could not find 'FootAppDrawing.png'.");
        }
    }

    private void scanForSensors() {
        if (footOutline == null) return;

        ArrayList<Point> rawPoints = new ArrayList<>();
        ArrayList<Point> allRedPixels = new ArrayList<>();
        for (int y = 0; y < footOutline.getHeight(); y++) {
            for (int x = 0; x < footOutline.getWidth(); x++) {
                int rgb = footOutline.getRGB(x, y);
                int a   = (rgb >> 24) & 0xFF;
                int r   = (rgb >> 16) & 0xFF;
                int g   = (rgb >>  8) & 0xFF;
                int b   =  rgb        & 0xFF;
                // Dominant-red: R > 140, R at least 80 more than G and B
                if (a > 0 && r > 140 && r - g > 80 && r - b > 50) {
                    allRedPixels.add(new Point(x, y));
                }
            }
        }
        ArrayList<ArrayList<Point>> clusters = new ArrayList<>();
        boolean[] used = new boolean[allRedPixels.size()];

        for (int i = 0; i < allRedPixels.size() && clusters.size() < 5; i++) {
            if (used[i]) continue;
            ArrayList<Point> cluster = new ArrayList<>();
            Point seed = allRedPixels.get(i);
            for (int j = i; j < allRedPixels.size(); j++) {
                if (!used[j] && seed.distance(allRedPixels.get(j)) < 15) {
                    cluster.add(allRedPixels.get(j));
                    used[j] = true;
                }
            }
            clusters.add(cluster);
        }
        for (ArrayList<Point> cluster : clusters) {
            int cx = 0, cy = 0;
            for (Point p : cluster) { cx += p.x; cy += p.y; }
            rawPoints.add(new Point(cx / cluster.size(), cy / cluster.size()));
        }

        if (rawPoints.size() < 5) {
            System.err.println("Warning: found " + rawPoints.size()
                + " sensor dots, expected 5.");
            return;
        }
        rawPoints.sort(Comparator.comparingInt(p -> p.y));
        Point pA = rawPoints.get(0);
        Point pB = rawPoints.get(1);
        if (pA.x > pB.x) { sensorPoints[0] = pA; sensorPoints[1] = pB; }
        else              { sensorPoints[0] = pB; sensorPoints[1] = pA; }
        sensorPoints[3] = rawPoints.get(2);
        Point pC = rawPoints.get(3);
        Point pD = rawPoints.get(4);
        if (pC.x < pD.x) { sensorPoints[2] = pC; sensorPoints[4] = pD; }
        else              { sensorPoints[2] = pD; sensorPoints[4] = pC; }
    }

    public void updateData(int[] fsr, int gaitLateral, int gaitSagittal, int[] avgPressure) {
        this.fsr          = fsr;
        this.gaitLateral  = gaitLateral;
        this.gaitSagittal = gaitSagittal;
        this.showHeatmap  = true;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (footOutline == null) return;

        Graphics2D g2 = (Graphics2D) g;
        int w = getWidth();
        int h = getHeight();

        double scale  = Math.min(w / (double) footOutline.getWidth(),
                                 (h - 150) / (double) footOutline.getHeight());
        int fW      = (int)(footOutline.getWidth()  * scale);
        int fH      = (int)(footOutline.getHeight() * scale);
        int startX  = (w - fW) / 2;
        int startY  = 20;

        for (int x = 0; x < fW; x++) {
            for (int y = 0; y < fH; y++) {
                int imgX = (int)(x / scale);
                int imgY = (int)(y / scale);
                if (imgX >= footOutline.getWidth() || imgY >= footOutline.getHeight()) continue;

                int rgb = footOutline.getRGB(imgX, imgY);
                if (((rgb >> 24) & 0xFF) < 10) continue;

                if (!showHeatmap) {
                    g2.setColor(new Color(220, 220, 220));
                } else {
                    double wSum = 0, tW = 0;
                    for (int i = 0; i < sensorPoints.length; i++) {
                        if (sensorPoints[i] == null) continue;
                        Point  p   = sensorPoints[i];
                        double sX  = p.x * scale;
                        double sY  = p.y * scale;
                        double dist = Math.sqrt(Math.pow(x - sX, 2) + Math.pow(y - sY, 2));
                        double intensity = (fsr[i] > 1000) ? 0.0
                                         : (1023.0 - fsr[i]) / 1023.0;
                        double weight = 1.0 / (Math.pow(dist, 2) + (300 * (scale / 5.0)));
                        wSum += intensity * weight;
                        tW   += weight;
                    }
                    g2.setColor(getViridisColor((float)(wSum / tW) * 4.0f));
                }
                g2.fillRect(startX + x, startY + y, 1, 1);
            }
        }

        if (showHeatmap) {
            g2.setColor(Color.DARK_GRAY);
            g2.setFont(new Font("SansSerif", Font.BOLD, 22));

            // Lateral component
            String lat = (gaitLateral == -1) ? "Left"
                       : (gaitLateral ==  1) ? "Right"
                       : "";

            // Sagittal component
            String sag = (gaitSagittal == -1) ? "Backward"
                       : (gaitSagittal ==  1) ? "Forward"
                       : "";

            // Combine: "Left Forward", "Right", "Forward", "Normal Gait", etc.
            String msg;
            if (lat.isEmpty() && sag.isEmpty()) {
                msg = "Normal Gait";
            } else if (lat.isEmpty()) {
                msg = sag + " Lean";
            } else if (sag.isEmpty()) {
                msg = lat + " Lean";
            } else {
                msg = lat + " + " + sag + " Lean";
            }

            g2.drawString(msg, (w - g2.getFontMetrics().stringWidth(msg)) / 2, h - 100);
        }

        drawKey(g2, w, h);
    }

    private void drawKey(Graphics2D g2, int w, int h) {
        int keyW = 150, keyH = 15;
        int kX   = w - keyW - 30;
        int kY   = h - 50;
        for (int i = 0; i < keyW; i++) {
            g2.setColor(getViridisColor((float) i / keyW));
            g2.drawLine(kX + i, kY, kX + i, kY + keyH);
        }
        g2.setColor(Color.DARK_GRAY);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g2.drawString("Low",          kX,              kY - 5);
        g2.drawString("High Pressure", kX + keyW - 80, kY - 5);
        g2.drawRect(kX, kY, keyW, keyH);
    }

    private Color getViridisColor(float val) {
        val = Math.max(0f, Math.min(1f, val));
        if (val < 0.25f) {
            return new Color((int)(40 + val * 100), 20, (int)(100 + val * 400));
        } else if (val < 0.7f) {
            float p = (val - 0.25f) / 0.45f;
            return new Color(20, (int)(100 + p * 155), (int)(180 - p * 50));
        } else {
            float p = (val - 0.7f) / 0.3f;
            return new Color((int)(p * 255), 255, (int)(130 - p * 130));
        }
    }
}
