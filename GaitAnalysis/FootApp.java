import javax.swing.*;
import java.awt.*;
import java.io.File;

public class FootApp {
    private static JButton calibrateBtn;
    private static JButton collectBtn;
    private static FootPanel footPanel;
    private static JFrame frame;
    private static JLabel statusLabel;

    private static Process bleProcess = null;

    public static void main(String[] args) {
        frame = new JFrame("Gait Analysis System");
        frame.setSize(500, 850);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                shutdownBleProcess();
                frame.dispose();
                System.exit(0);
            }
        });
        frame.setLayout(new BorderLayout());

        int[] initialFsr = {1023, 1023, 1023, 1023, 1023};
        footPanel = new FootPanel(initialFsr, 0, 0, initialFsr);
        frame.add(footPanel, BorderLayout.CENTER);

        statusLabel = new JLabel("System Ready", SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        frame.add(statusLabel, BorderLayout.NORTH);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 15));
        JButton interpretBtn = new JButton("Interpret");
        calibrateBtn = new JButton("Calibrate");
        collectBtn   = new JButton("Start");

        interpretBtn.setPreferredSize(new Dimension(110, 40));
        calibrateBtn.setPreferredSize(new Dimension(110, 40));
        collectBtn.setPreferredSize(new Dimension(110, 40));

        if (!new File("calibration.json").exists()) {
            collectBtn.setEnabled(false);
            statusLabel.setText("No calibration found. Press Calibrate first.");
        } else {
            statusLabel.setText("Calibration loaded. Press Start to collect data.");
        }

        controls.add(interpretBtn);
        controls.add(calibrateBtn);
        controls.add(collectBtn);
        frame.add(controls, BorderLayout.SOUTH);
        interpretBtn.addActionListener(e -> {
            if (!new File("norm_stats").exists()) {
                statusLabel.setText("Error: norm_stats missing. Retrain the model.");
                return;
            }

            tensor t  = tensor.loadTensor("trained");
            tensor t2 = tensor.loadTensor("trained2");
            double[][] data = tensor.fetchData("data.csv");

            if (t == null || t2 == null || data == null || data.length == 0) {
                statusLabel.setText("Error: Data or Tensors missing");
                return;
            }

            Main.applyGlobalStats(data);

            double[][] rawForDisplay = tensor.fetchData("data.csv");
            int[] avgFsr = new int[5];
            double[] sums = new double[5];
            for (double[] row : rawForDisplay) {
                sums[0] += row[4]; // FSR1 front-right
                sums[1] += row[5]; // FSR2 front-left
                sums[2] += row[6]; // FSR3 bottom-left
                sums[3] += row[7]; // FSR4 middle
                sums[4] += row[8]; // FSR5 bottom-right
            }
            for (int j = 0; j < 5; j++) {
                avgFsr[j] = (int)(sums[j] / rawForDisplay.length);
            }

            int gaitLateral  = Main.calculate(t,   data);
            int gaitSagittal = Main.calculate2(t2, data);

            footPanel.updateData(avgFsr, gaitLateral, gaitSagittal, avgFsr);
            statusLabel.setText("Gait: " + describeGait(gaitLateral, gaitSagittal));
        });

        calibrateBtn.addActionListener(e -> startCalibrationSequence());

        collectBtn.addActionListener(e -> {
            if (collectBtn.getText().equals("Start")) {
                startDataCollection();
            } else if (collectBtn.getText().equals("Stop")) {
                stopDataCollection();
            }
        });

        frame.setVisible(true);
    }

    private static void startCalibrationSequence() {
        launchBleCollectorIfNeeded();
        calibrateBtn.setEnabled(false);
        collectBtn.setEnabled(false);

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                publish("Leave the shoe still for calibration...");
                sendCommandToBle("c\n");
                waitForPythonSignal("done_static.txt");

                publish("Move the shoe to the RIGHT...");
                waitForPythonSignal("done_right.txt");

                publish("Move the shoe FORWARD...");
                waitForPythonSignal("done_forward.txt");

                return null;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                statusLabel.setText("Calibration complete. Press Start to collect data.");
                calibrateBtn.setEnabled(true);
                collectBtn.setEnabled(true);
                collectBtn.setText("Start");
            }
        };
        worker.execute();
    }

    private static void startDataCollection() {
        launchBleCollectorIfNeeded();
        sendCommandToBle("s\n");
        statusLabel.setText("Collecting data...");
        collectBtn.setText("Stop");
        calibrateBtn.setEnabled(false);
    }

    private static void stopDataCollection() {
        sendCommandToBle("p\n");
        statusLabel.setText("Data saved to data.csv. Press Start for another burst.");
        collectBtn.setText("Start");
        calibrateBtn.setEnabled(true);
    }

    private static String describeGait(int lateral, int sagittal) {
        String lat = (lateral == -1) ? "Left"     : (lateral == 1) ? "Right"   : "";
        String sag = (sagittal == -1) ? "Backward" : (sagittal == 1) ? "Forward" : "";
        if (lat.isEmpty() && sag.isEmpty()) return "Normal Gait";
        if (lat.isEmpty())  return sag + " Lean";
        if (sag.isEmpty())  return lat + " Lean";
        return lat + " + " + sag + " Lean";
    }

    private static void shutdownBleProcess() {
        if (bleProcess == null) return;
        if (!bleProcess.isAlive()) {
            bleProcess = null;
            return;
        }
        try {
            bleProcess.getOutputStream().write("q\n".getBytes());
            bleProcess.getOutputStream().flush();
        } catch (Exception ignored) {}
        try {
            if (!bleProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                bleProcess.destroyForcibly();
                bleProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            bleProcess.destroyForcibly();
        }
        bleProcess = null;
    }

    private static void launchBleCollectorIfNeeded() {
        if (bleProcess != null && bleProcess.isAlive()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder("python", "-u", "ble_collector.py");
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            bleProcess = pb.start();

            final java.io.InputStream pyOut = bleProcess.getInputStream();
            Thread drainer = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(pyOut))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        System.out.println("[BLE] " + line);
                    }
                } catch (Exception ignored) {}
            });
            drainer.setDaemon(true);
            drainer.start();

        } catch (Exception e) {
            System.err.println("Failed to launch ble_collector.py: " + e.getMessage());
        }
    }

    private static void sendCommandToBle(String command) {
        if (bleProcess == null || !bleProcess.isAlive()) {
            System.err.println("BLE process not running.");
            return;
        }
        try {
            bleProcess.getOutputStream().write((command.trim() + "\n").getBytes());
            bleProcess.getOutputStream().flush();
        } catch (Exception e) {
            System.err.println("Failed to send command to BLE process: " + e.getMessage());
        }
    }

    private static void waitForPythonSignal(String fileName) throws InterruptedException {
        File signalFile = new File(fileName);
        while (!signalFile.exists()) {
            Thread.sleep(500);
        }
        signalFile.delete();
    }
}
