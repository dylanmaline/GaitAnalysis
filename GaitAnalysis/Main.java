import java.lang.reflect.Array;
import java.util.*;
import java.io.*;

public class Main {
    private static double[] globalMean;
    private static double[] globalStd;
    private static final double LATERAL_THRESHOLD  = 4.0;
    private static final double SAGITTAL_THRESHOLD = 10.0;
    public static void main(String[] args) {
        tensor t  = tensor.loadTensor("trained");
        tensor t2 = tensor.loadTensor("trained2");
        if (t == null) {
            int[] shape = {6, 20};
            t = new tensor(2, shape, "lateral");
        }
        if (t2 == null) {
            int[] shape = {6, 20};
            t2 = new tensor(2, shape, "sagittal");
        }

        String[] files = {
            "data1.csv", "data2.csv", "data3.csv",      // normal
            "data4.csv", "data5.csv", "data6.csv",      // right bias
            "data7.csv", "data8.csv", "data9.csv",      // left bias
            "data10.csv","data11.csv","data12.csv",     // back bias
            "data13.csv","data14.csv","data15.csv",     // front bias
            "data16.csv","data17.csv","data18.csv",     // normal
        };

        int[][] labels = {
            { 0,  0}, { 0,  0}, { 0,  0},    // 1-3   normal
            { 1,  0}, { 1,  0}, { 1,  0},    // 4-6   right
            {-1,  0}, {-1,  0}, {-1,  0},    // 7-9   left
            { 0, -1}, { 0, -1}, { 0, -1},    // 10-12 back
            { 0,  1}, { 0,  1}, { 0,  1},    // 13-15 front
            { 0,  0}, { 0,  0}, { 0,  0},    // 16-18 normal
        };
        double[][][] data = new double[files.length][][];
        for (int i = 0; i < files.length; i++) {
            data[i] = tensor.fetchData(files[i]);
        }

        computeGlobalStats(data);  

        for (int i = 0; i < files.length; i++) {
            applyGlobalStats(data[i]);
        }

        tensor[][] tensors = new tensor[20][2];
        tensors[0][0] = t;
        tensors[0][1] = t2;
        for (int i = 1; i < 20; i++) {
            tensors[i][0] = new tensor(t,  2);
            tensors[i][1] = new tensor(t2, 2);
        }

        tensor[] result = train(tensors, data, labels, 5.0, 300);

        System.out.println("\n=== raw averaged predictions ===");
        for (int d = 0; d < data.length; d++) {
            double latAvg = rawSlidingAvg(result[0], data[d], new int[]{2,3,4,5,6,8});
            double sagAvg = rawSlidingAvg(result[1], data[d], new int[]{1,3,4,5,6,8});
            System.out.printf("%-12s  lat=%8.3f  sag=%8.3f  label={%2d,%2d}%n",
                files[d], latAvg, sagAvg, labels[d][0], labels[d][1]);
        }
        System.out.println("\n=== post-training sanity check ===");
        int hits = 0;
        for (int d = 0; d < data.length; d++) {
            int p1 = calculate(result[0],  data[d]);
            int p2 = calculate2(result[1], data[d]);
            boolean ok = (p1 == labels[d][0] && p2 == labels[d][1]);
            if (ok) hits++;
            System.out.printf("%-12s predicted {%2d,%2d}  actual {%2d,%2d}  %s%n",
                files[d], p1, p2, labels[d][0], labels[d][1], ok ? "OK" : "MISS");
        }
        System.out.printf("Training accuracy: %d / %d%n", hits, data.length);

        tensor.saveTensor(result[0], "trained");
        tensor.saveTensor(result[1], "trained2");
    }
    public static tensor[] train(
            tensor[][] tensors,
            double[][][] data,
            int[][] labels,
            double factor,
            int desiredCycles
    ) {

        final double MIN_FACTOR = 0.3;

        for (int cycle = 0; cycle < desiredCycles; cycle++) {

            int[][] scores = new int[tensors.length][2];

            for (int d = 0; d < data.length; d++) {
                double[][] data2 = data[d];
                for (int j = 0; j < tensors.length; j++) {
                    int p1 = calculate(tensors[j][0],  data2);
                    int p2 = calculate2(tensors[j][1], data2);
                    if (p1 == labels[d][0]) scores[j][0]++; else scores[j][0]--;
                    if (p2 == labels[d][1]) scores[j][1]++; else scores[j][1]--;
                }
            }

            bubbleSortByScore(tensors, scores, 0);

            tensor[][] newTensors = new tensor[tensors.length][2];
            int slot = 0, attempts = 0;
            double percent = 0.99;
            while (slot < tensors.length && attempts < 10000) {
                for (int g = 0; g < tensors.length && slot < tensors.length; g++) {
                    if (Math.random() < Math.pow(percent, g)) {
                        newTensors[slot][0] = new tensor(tensors[g][0], factor);
                        slot++;
                    }
                }
                attempts++;
            }

            bubbleSortByScore(tensors, scores, 1);

            slot = 0;
            while (slot < tensors.length) {
                for (int g = 0; g < tensors.length && slot < tensors.length; g++) {
                    if (Math.random() < Math.pow(percent, g)) {
                        newTensors[slot][1] = new tensor(tensors[g][1], factor);
                        slot++;
                    }
                }
            }

            if (cycle % 20 == 0 || cycle == desiredCycles - 1) {
                System.out.printf(
                    "cycle %4d  bestLat=%3d  bestSag=%3d  factor=%.4f%n",
                    cycle, scores[0][0], scores[0][1], factor);
            }

            tensors = newTensors;
            factor = Math.max(factor * 0.9, MIN_FACTOR);
        }

        return tensors[0];
    }

    private static void bubbleSortByScore(tensor[][] tensors, int[][] scores, int axis) {
        int n = tensors.length;
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < n - i - 1; j++) {
                if (scores[j][axis] < scores[j + 1][axis]) {
                    int tmp = scores[j][0]; scores[j][0] = scores[j+1][0]; scores[j+1][0] = tmp;
                    tmp     = scores[j][1]; scores[j][1] = scores[j+1][1]; scores[j+1][1] = tmp;
                    tensor t0 = tensors[j][0]; tensor t1 = tensors[j][1];
                    tensors[j][0]   = tensors[j+1][0]; tensors[j][1]   = tensors[j+1][1];
                    tensors[j+1][0] = t0;              tensors[j+1][1] = t1;
                }
            }
        }
    }

    private static double slidingWindowPrediction(
            tensor t, double[][] data, int[] cols, int windowSize) {

        int numCols = cols.length;

        if (data.length < windowSize) {
            int timeSteps = data.length;
            double prediction = 0;
            for (int i = 0; i < timeSteps; i++) {
                for (int j = 0; j < numCols; j++) {
                    int idx = i * numCols + j;
                    if (idx < t.flattenedValues.length) {
                        prediction += t.flattenedValues[idx] * data[i][cols[j]];
                    }
                }
            }
            return prediction;
        }

        double total = 0;
        int windows = 0;
        int lastStart = data.length - windowSize;

        for (int start = 0; start <= lastStart; start++) {
            double windowPrediction = 0;
            for (int i = 0; i < windowSize; i++) {
                double[] row = data[start + i];
                for (int j = 0; j < numCols; j++) {
                    int idx = i * numCols + j;
                    if (idx < t.flattenedValues.length) {
                        windowPrediction += t.flattenedValues[idx] * row[cols[j]];
                    }
                }
            }
            total += windowPrediction;
            windows++;
        }

        return total / windows;
    }

    // calculate -- lateral (left/right) tensor
    public static int calculate(tensor t, double[][] data) {
        int[] lateralCols = {2, 3, 4, 5, 6, 8};
        double avg = slidingWindowPrediction(t, data, lateralCols, 20);
        if (avg < -LATERAL_THRESHOLD) return -1;
        if (avg >  LATERAL_THRESHOLD) return  1;
        return 0;
    }

    // calculate2 -- sagittal (forward/backward) tensor
    public static int calculate2(tensor t, double[][] data) {
        int[] sagittalCols = {1, 3, 4, 5, 6, 8};
        double avg = slidingWindowPrediction(t, data, sagittalCols, 20);
        if (avg < -SAGITTAL_THRESHOLD) return -1;
        if (avg >  SAGITTAL_THRESHOLD) return  1;
        return 0;
    }
    public static double rawSlidingAvg(tensor t, double[][] data, int[] cols) {
        return slidingWindowPrediction(t, data, cols, 20);
    }

    public static void computeGlobalStats(double[][][] allData) {
        if (allData == null || allData.length == 0) return;

        int cols = -1;
        for (double[][] f : allData) {
            if (f != null && f.length > 0) { cols = f[0].length; break; }
        }
        if (cols < 0) return;

        globalMean = new double[cols];
        globalStd  = new double[cols];

        long n = 0;
        for (double[][] file : allData) {
            if (file == null) continue;
            for (double[] row : file) {
                for (int c = 0; c < cols; c++) globalMean[c] += row[c];
                n++;
            }
        }
        if (n == 0) return;
        for (int c = 0; c < cols; c++) globalMean[c] /= n;

        for (double[][] file : allData) {
            if (file == null) continue;
            for (double[] row : file) {
                for (int c = 0; c < cols; c++) {
                    double d = row[c] - globalMean[c];
                    globalStd[c] += d * d;
                }
            }
        }
        for (int c = 0; c < cols; c++) {
            globalStd[c] = Math.sqrt(globalStd[c] / n);
            if (globalStd[c] < 1e-9) globalStd[c] = 1.0;
        }

        saveStats();
    }

    public static void applyGlobalStats(double[][] data) {
        if (globalMean == null || globalStd == null) loadStats();
        if (globalMean == null || data == null) return;

        for (double[] row : data) {
            int limit = Math.min(row.length, globalMean.length);
            for (int c = 1; c < limit; c++) {     // skip time column
                row[c] = (row[c] - globalMean[c]) / globalStd[c];
            }
        }
    }

    private static void saveStats() {
        try (ObjectOutputStream out = new ObjectOutputStream(
                new FileOutputStream("norm_stats"))) {
            out.writeObject(globalMean);
            out.writeObject(globalStd);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadStats() {
        try (ObjectInputStream in = new ObjectInputStream(
                new FileInputStream("norm_stats"))) {
            globalMean = (double[]) in.readObject();
            globalStd  = (double[]) in.readObject();
        } catch (Exception e) {
            globalMean = null;
            globalStd  = null;
        }
    }
}


class tensor implements Serializable {

    public  String   name;
    private int      TDC;
    private int[]    shape;
    public  Object   values;
    public  int      size;
    public  double[] flattenedValues;
    private int      offSpring;

    public tensor(int TDC, int[] shape, String name) {
        this.name = name;
        int sz = 1;
        for (int i = 0; i < TDC; i++) sz *= shape[i];
        this.flattenedValues = new double[sz];
        for (int i = 0; i < sz; i++) this.flattenedValues[i] = Math.random() - 0.5;
        this.values = Array.newInstance(double.class, shape);
        fillValues(this.values, this.flattenedValues, new int[shape.length], 0);
        this.TDC   = TDC;
        this.shape = shape;
        this.size  = sz;
    }

    public tensor(tensor t, double factor) {
        t.offSpring++;
        this.name            = t.name + t.offSpring;
        this.flattenedValues = t.flattenedValues.clone();
        for (int i = 0; i < this.flattenedValues.length; i++) {
            this.flattenedValues[i] += (Math.random() - 0.5) * factor;
        }
        this.values = Array.newInstance(double.class, t.shape);
        fillValues(this.values, this.flattenedValues, new int[t.shape.length], 0);
        this.TDC   = t.TDC;
        this.shape = t.shape;
        this.size  = t.size;
    }

    public static int fillValues(Object A, double[] flat, int[] idx, int index) {
        int dim = Array.getLength(A);
        for (int i = 0; i < dim; i++) {
            Object slice = Array.get(A, i);
            if (slice.getClass().isArray()) {
                int[] newIdx = Arrays.copyOf(idx, idx.length + 1);
                newIdx[idx.length] = i;
                index = fillValues(slice, flat, newIdx, index);
            } else {
                Array.setDouble(A, i, flat[index++]);
            }
        }
        return index;
    }

    public static void saveTensor(tensor t, String fileName) {
        try (ObjectOutputStream out =
                     new ObjectOutputStream(new FileOutputStream(fileName))) {
            out.writeObject(t);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static tensor loadTensor(String fileName) {
        try (ObjectInputStream in =
                new ObjectInputStream(new FileInputStream(fileName))) {
            return (tensor) in.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    public static double[][] fetchData(String path) {
        List<double[]> dataList = new ArrayList<>();
        double pollingRate = 0.2;

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine(); // skip header row

            String line;
            int rowCounter = 0;

            while ((line = br.readLine()) != null) {
                String[] vals = line.split(",");
                if (vals.length < 8) continue;

                double[] row = new double[9];
                row[0] = rowCounter * pollingRate;
                for (int i = 0; i < 8; i++) {
                    row[i + 1] = Double.parseDouble(vals[i].trim());
                }
                dataList.add(row);
                rowCounter++;
            }
        } catch (Exception e) {
            return new double[0][0];
        }

        return dataList.toArray(new double[0][0]);
    }
}
