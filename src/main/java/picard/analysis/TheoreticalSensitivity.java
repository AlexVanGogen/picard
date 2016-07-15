/*
 * The MIT License
 *
 * Copyright (c) 2015 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package picard.analysis;

import com.google.common.util.concurrent.FutureFallback;
import htsjdk.samtools.util.Histogram;
import htsjdk.samtools.util.Log;
import picard.PicardException;
import picard.util.MathUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Created by David Benjamin on 5/13/15.
 */
public class TheoreticalSensitivity {

    private static final Log log = Log.getInstance(TheoreticalSensitivity.class);
    private static final int SAMPLING_MAX = 600; //prevent 'infinite' loops
    private static final int MAX_CONSIDERED_DEPTH = 1000; //no point in looking any deeper than this, otherwise GC overhead is too high.

    /**
     * @param depthDistribution the probability of depth n is depthDistribution[n] for n = 0, 1. . . N - 1
     * @param qualityDistribution the probability of quality q is qualityDistribution[q] for q = 0, 1. . . Q
     * @param sampleSize sample size is the number of random sums of quality scores for each m
     * @param logOddsThreshold is the log_10 of the likelihood ratio required to call a SNP,
     * for example 5 if the variant likelihood must be 10^5 times greater
     */
    public static double hetSNPSensitivity(final double[] depthDistribution, final double[] qualityDistribution,
                                           final int sampleSize, final double logOddsThreshold) {
        return hetSNPSensitivity(depthDistribution, qualityDistribution, sampleSize, logOddsThreshold, true);
    }

    /**
     * @param depthDistribution the probability of depth n is depthDistribution[n] for n = 0, 1. . . N - 1
     * @param qualityDistribution the probability of quality q is qualityDistribution[q] for q = 0, 1. . . Q
     * @param sampleSize sample size is the number of random sums of quality scores for each m
     * @param logOddsThreshold is the log_10 of the likelihood ratio required to call a SNP,
     * for example 5 if the variant likelihood must be 10^5 times greater.
     * @param withLogging true to output log messages, false otherwise.
     */
    public static double hetSNPSensitivity(final double[] depthDistribution, final double[] qualityDistribution,
                                           final int sampleSize, final double logOddsThreshold, final boolean withLogging) {
        final int N = Math.min(depthDistribution.length, MAX_CONSIDERED_DEPTH + 1);

        if (withLogging) log.info("Creating Roulette Wheel");
        final RouletteWheel qualitySampler = new RouletteWheel(qualityDistribution);

        //qualitySums[m] is a random sample of sums of m quality scores, for m = 0, 1, N - 1
        if (withLogging) log.info("Calculating quality sums from quality sampler");
        final List<ArrayList<Integer>> qualitySums = qualitySampler.sampleCumulativeSums(N, sampleSize, withLogging);

        //if a quality sum of m qualities exceeds the quality sum threshold for n total reads, a SNP is called
        final ArrayList<Double> qualitySumThresholds = new ArrayList<>(N);
        final double LOG_10 = Math.log10(2);

        for (int n = 0; n < N; n++) qualitySumThresholds.add(10 * (n * LOG_10 + logOddsThreshold));

        //probabilityToExceedThreshold[m][n] is the probability that the sum of m quality score
        //exceeds the nth quality sum threshold
        if (withLogging) log.info("Calculating theoretical het sensitivity");
        final List<ArrayList<Double>> probabilityToExceedThreshold = proportionsAboveThresholds(qualitySums, qualitySumThresholds);
        final List<ArrayList<Double>> altDepthDistribution = hetAltDepthDistribution(N);
        double result = 0.0;
        for (int n = 0; n < N; n++) {
            for (int m = 0; m <= n; m++) {
                result += depthDistribution[n] * altDepthDistribution.get(n).get(m) * probabilityToExceedThreshold.get(m).get(n);
            }
        }
        return result;
    }

    //given L lists of lists and N thresholds, count the proportion of each list above each threshold

    /**
     * Массив lists[i] представляет собой что-то вроде случайной выборки.
     * Нам известно, что lists[a][i] <= lists[b][i] для любого i и для любых a и b: a < b.
     * Проходим по первому индексу, в newRow[i] записываем вероятность того, что в случайной выборке
     * ровно j элементов меньше i-го значения порога.
     *
     * @param lists
     * @param thresholds
     * @return
     */
    public static List<ArrayList<Double>> proportionsAboveThresholds(final List<ArrayList<Integer>> lists, final List<Double> thresholds) {
        final ArrayList<ArrayList<Double>> result = new ArrayList<>();

        for (final ArrayList<Integer> list : lists) {
            final ArrayList<Double> newRow = new ArrayList<>(Collections.nCopies(thresholds.size(), 0.0));
            Collections.sort(list);
            int n = 0;
            int j = 0;  //index within the ordered sample
            while (n < thresholds.size() && j < list.size()) {
                if (thresholds.get(n) > list.get(j)) j++;
                else newRow.set(n++, (double) (list.size() - j) / list.size());
            }
            result.add(newRow);
        }
        return result;
    }

    //Utility function for making table of binomial distribution probabilities nCm * (0.5)^n
    //for n = 0, 1 . . . N - 1 and m = 0, 1. . . n
    public static List<ArrayList<Double>> hetAltDepthDistribution(final int N) {
        final List<ArrayList<Double>> table = new ArrayList<>();
        for (int n = 0; n < N; n++) {
            final ArrayList<Double> nthRow = new ArrayList<>();

            //add the 0th element, then elements 1 through n - 1, then the nth.
            //Note that nCm = (n-1)C(m-1) * (n/m)
            nthRow.add(Math.pow(0.5, n));
            for (int m = 1; m < n; m++) nthRow.add((n * 0.5 / m) * table.get(n - 1).get(m - 1));
            if (n > 0) nthRow.add(nthRow.get(0));

            table.add(nthRow);
        }
        return table;
    }

    /*
    Perform random draws from {0, 1. . . N - 1} according to a list of relative probabilities.

    We use an O(1) stochastic acceptance algorithm -- see Physica A, Volume 391, Page 2193 (2012) --
    which works well when the ratio of maximum weight to average weight is not large.
     */
    public static class RouletteWheel {
        final private List<Double> probabilities;
        final private int N;
        private LongAdder count = new LongAdder();
        private Random rng;

        RouletteWheel(final double[] weights) {
            rng = new Random(51);
            N = weights.length;
            probabilities = new ArrayList<>();
            final double wMax = MathUtil.max(weights);

            if (wMax == 0) {
                throw new PicardException("Quality score distribution is empty.");
            }

            for (final double w : weights) {
                probabilities.add(w / wMax);
            }
        }

        public int draw() {
            while (true) {
                final int n = (int) (N * rng.nextDouble());
                count.increment();
                if (rng.nextDouble() < probabilities.get(n)) {
                    count.reset();
                    return n;
                } else if (count.intValue() >= SAMPLING_MAX) {
                    count.reset();
                    return 0;
                }
            }
        }

        /**
         * Что нужно сделать?
         * 1 поток все время читает, несколько потоков обрабатывают
         */
        private final int SAMPLES_PER_TASK = 1000;
        private final int MAX_THREADS = 8;
        //get samples of sums of 0, 1, 2,. . .  N - 1 draws
        public List<ArrayList<Integer>> sampleCumulativeSums(final int maxNumberOfSummands, final int sampleSize, final boolean withLogging) {
            long start = System.nanoTime();
            final List<Integer[]> result = new ArrayList<>();
            for (int m = 0; m < maxNumberOfSummands; m++) { result.add(new Integer[sampleSize]); }
            //int leftBound  = 0;  // rightBound = min(leftBound + SAMPLES_PER_TASK, sampleSize);
            ExecutorService es = Executors.newFixedThreadPool(MAX_THREADS);
            List<Future<List<Integer[]>>> results = new ArrayList<>();
            for (int leftBound = 0; leftBound < sampleSize; leftBound += SAMPLES_PER_TASK) {
                final int tmpLeftBound = leftBound, tmpRightBound = Math.min(leftBound + SAMPLES_PER_TASK, sampleSize);
                //leftBound = tmpRightBound;
                Future<List<Integer[]>> nextTaskResult = es.submit(() -> {
                    log.info("Start task");
                    List<Integer[]> res = new ArrayList<>();
                    for (int m = 0; m < maxNumberOfSummands; m++) { res.add(new Integer[sampleSize]); }
//                    long st = System.nanoTime();
                    for (int iteration = tmpLeftBound; iteration < tmpRightBound; iteration++) {
                        int cumulativeSum = 0;
                        for (int m = 0; m < maxNumberOfSummands; m++) {
                            res.get(m)[iteration] = cumulativeSum;
                            cumulativeSum += draw();
                        }
                        if (withLogging && (iteration+1) % SAMPLES_PER_TASK == 0) {
                            log.info((iteration + 1) + " sampling iterations completed");
                        }
                    }
//                    System.out.println("===> Time: " + (System.nanoTime() - st));
                    return res;
                });
                results.add(nextTaskResult);
            }

            es.shutdown();
            try {
                es.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(results.size());
            for (Future<List<Integer[]>> res : results) {
                try {
                    List<Integer[]> sample = res.get();
                    for (int i = 0; i < maxNumberOfSummands; i++) {
                        Integer[] valuesOfSummand = sample.get(i);
                        result.set(i, Arrays.copyOf(valuesOfSummand, valuesOfSummand.length));
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
            long stop = System.nanoTime();
            System.out.println("Time elapsed for cumulativeSums: " + (stop - start) + " ns.");
//            File file = new File("D:\\\\EPAM\\SummerPractice\\picardmy\\lol.txt");
//            try {
//                FileWriter fileWriter = new FileWriter(file);
//                for (int i = 0; i < maxNumberOfSummands; i++) {
//                    for (int j = 0; j < sampleSize; j++)
//                        if (result.get(i)[j] == null)
//                            fileWriter.write((i*maxNumberOfSummands+j) + " ");
//                    System.out.println();
//                }
//            } catch (IOException e) {
//                e.printStackTrace();
//            }

            List<ArrayList<Integer>> res = new ArrayList<>();
            for (int i = 0; i < maxNumberOfSummands; i++)
                res.add(new ArrayList<>(Arrays.asList(result.get(i))));
            return res;
        }
//        public List<ArrayList<Integer>> sampleCumulativeSums(final int maxNumberOfSummands, final int sampleSize, final boolean withLogging) {
//            long start = System.nanoTime();
//            final List<ArrayList<Integer>> result = new ArrayList<>();
//            for (int m = 0; m < maxNumberOfSummands; m++) result.add(new ArrayList<>(sampleSize));
//
//            for (int iteration = 0; iteration < sampleSize; iteration++) {
//                int cumulativeSum = 0;
//                for (int m = 0; m < maxNumberOfSummands; m++) {
//                    result.get(m).add(iteration, cumulativeSum);
//                    cumulativeSum += draw();
//                }
//                if (withLogging && iteration % SAMPLES_PER_TASK == 0) {
//                    log.info(iteration + " sampling iterations completed");
//                }
//            }
//            long stop = System.nanoTime();
//            System.out.println("Time elapsed for cumulativeSums: " + (stop - start) + " ns.");
//            return result;
//        }
    }

    public static double[] normalizeHistogram(final Histogram<Integer> histogram) {
        if (histogram == null) throw new PicardException("Histogram is null and cannot be normalized");

        final double histogramSumOfValues = histogram.getSumOfValues();
        final double[] normalizedHistogram = new double[histogram.size()];

        for (int i = 0; i < histogram.size(); i++) {
            normalizedHistogram[i] = histogram.get(i).getValue() / histogramSumOfValues;
        }
        return normalizedHistogram;
    }
}
