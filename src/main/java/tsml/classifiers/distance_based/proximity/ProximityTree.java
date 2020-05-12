package tsml.classifiers.distance_based.proximity;

import com.beust.jcommander.internal.Lists;
import evaluation.storage.ClassifierResults;
import experiments.data.DatasetLoading;
import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import java.util.Set;
import org.junit.Assert;
import tsml.classifiers.TrainTimeContractable;
import tsml.classifiers.distance_based.distances.DistanceMeasureConfigs;
import tsml.classifiers.distance_based.proximity.splitting.BestOfNSplits;
import tsml.classifiers.distance_based.proximity.splitting.Split;
import tsml.classifiers.distance_based.proximity.splitting.Splitter;
import tsml.classifiers.distance_based.proximity.splitting.exemplar_based.ContinuousDistanceFunctionConfigs;
import tsml.classifiers.distance_based.proximity.splitting.exemplar_based.RandomDistanceFunctionPicker;
import tsml.classifiers.distance_based.proximity.splitting.exemplar_based.RandomExemplarPerClassPicker;
import tsml.classifiers.distance_based.proximity.splitting.exemplar_based.RandomExemplarProximitySplit;
import tsml.classifiers.distance_based.proximity.stopping_conditions.Pure;
import tsml.classifiers.distance_based.utils.classifier_mixins.BaseClassifier;
import tsml.classifiers.distance_based.utils.classifier_mixins.TestTimeable;
import tsml.classifiers.distance_based.utils.classifier_mixins.Utils;
import tsml.classifiers.distance_based.utils.iteration.LinearListIterator;
import tsml.classifiers.distance_based.utils.params.ParamSpace;
import tsml.classifiers.distance_based.utils.tree.BaseTree;
import tsml.classifiers.distance_based.utils.tree.BaseTreeNode;
import tsml.classifiers.distance_based.utils.tree.Tree;
import tsml.classifiers.distance_based.utils.tree.TreeNode;
import tsml.filters.CachedFilter;
import weka.core.Instance;
import weka.core.Instances;

/**
 * Purpose: proximity tree
 * <p>
 * Contributors: goastler
 */
public class ProximityTree extends BaseClassifier implements TrainTimeContractable, TestTimeable {

    public static void main(String[] args) throws Exception {
        int seed = 1;
        ProximityTree classifier = new ProximityTree();
        classifier.setEstimateOwnPerformance(true);
        classifier.setSeed(seed);
        classifier.setConfigR1();
        Utils.trainTestPrint(classifier, DatasetLoading.sampleGunPoint(seed));
    }

    // -------------------- configs --------------------

    public ProximityTree setConfigR1() {
        // todo paper
        setBuildUntilPure();
        return setSingleSplit();
    }

    public ProximityTree setConfigR5() {
        setBuildUntilPure();
        return set5Splits();
    }

    public ProximityTree setConfigR10() {
        setBuildUntilPure();
        return set10Splits();
    }

    // -------------------- end configs --------------------

    public ProximityTree() {
        super(CAN_ESTIMATE_OWN_PERFORMANCE);
    }

    public ProximityTree setBuildUntilPure() {
        return setStoppingCondition(new Pure());
    }

    public ProximityTree setSingleSplit() {
        setRebuildListener(trainData -> {
            final Random random = getRandom();
            final RandomExemplarPerClassPicker exemplarPicker = new RandomExemplarPerClassPicker(random);
            final List<ParamSpace> paramSpaces = Lists.newArrayList(
                DistanceMeasureConfigs.buildEdSpace(),
                DistanceMeasureConfigs.buildFullDtwSpace(),
                DistanceMeasureConfigs.buildFullDdtwSpace(),
                ContinuousDistanceFunctionConfigs.buildDtwSpace(trainData),
                ContinuousDistanceFunctionConfigs.buildDdtwSpace(trainData),
                ContinuousDistanceFunctionConfigs.buildErpSpace(trainData),
                ContinuousDistanceFunctionConfigs.buildLcssSpace(trainData),
                DistanceMeasureConfigs.buildMsmSpace(),
                ContinuousDistanceFunctionConfigs.buildWdtwSpace(),
                ContinuousDistanceFunctionConfigs.buildWddtwSpace(),
                DistanceMeasureConfigs.buildTwedSpace()
            );
            final RandomDistanceFunctionPicker distanceFunctionPicker = new RandomDistanceFunctionPicker(
                random, paramSpaces);
            setSplitter(data -> {
                RandomExemplarProximitySplit split = new RandomExemplarProximitySplit(
                    random, exemplarPicker,
                    distanceFunctionPicker);
                split.setData(data);
                return split;
            });
        });
        return this;
    }

    public ProximityTree setMultipleSplits(int numSplits) {
        Assert.assertTrue(numSplits > 0);
        Splitter splitter = getSplitter();
        setSplitter(data -> {
            BestOfNSplits bestOfNSplits = new BestOfNSplits(splitter, this.getRandom(), numSplits);
            bestOfNSplits.setData(data);
            return bestOfNSplits;
        });
        return this;
    }

    public ProximityTree set5Splits() {
        return setMultipleSplits(5);
    }

    public ProximityTree set10Splits() {
        return setMultipleSplits(10);
    }

    private final Tree<Split> tree = new BaseTree<>();
    private final TimeContracter trainTimeContracter = new TimeContracter();
    private final TimeContracter testTimeContracter = new TimeContracter();
    private final MemoryContracter trainMemoryContracter = new MemoryContracter();
    private final MemoryContracter testMemoryContracter = new MemoryContracter();
    private long longestNodeBuildTimeNanos;
    private ListIterator<TreeNode<Split>> nodeBuildQueue = new LinearListIterator<>();
    private StoppingCondition stoppingCondition;
    private Splitter splitter;
    private Instances oobTrain;
    private Instances oobTest;

    public interface StoppingCondition extends Serializable {
        boolean shouldStop(TreeNode<Split> node);
    }

    @Override
    public void buildClassifier(Instances trainData) throws Exception {
        trainMemoryContracter.getWatcher().enable();
        trainTimeContracter.getTimer().enable();
        if(isRebuild()) {
            // reset
            trainMemoryContracter.getWatcher().resetAndEnable();
            trainTimeContracter.getTimer().resetAndEnable();
            super.buildClassifier(trainData);
            // make the instances hashed so caching of distances works
            CachedFilter.hashInstances(trainData);
            if(getEstimateOwnPerformance()) {
                oobTrain = new Instances(trainData, 0);
                final Set<Instance> oobTestSet = new HashSet<>(trainData);
                for(int i = 0; i < trainData.size(); i++) {
                    Instance instance = trainData.get(rand.nextInt(trainData.size()));
                    oobTrain.add(instance);
                    oobTestSet.remove(instance);
                }
                oobTest = new Instances(trainData, 0);
                oobTest.addAll(oobTestSet);
                trainData = oobTrain;
            }
            tree.clear();
            longestNodeBuildTimeNanos = 0;
            nodeBuildQueue = new LinearListIterator<>();
            final TreeNode<Split> root = buildNode(trainData, null);
            tree.setRoot(root);
        }
        while(
            // there is enough time for another split to be built
            (!this.trainTimeContracter.hasTimeLimit()
                ||
                this.trainTimeContracter.getRemainingTrainTime() < longestNodeBuildTimeNanos)
            // and there's remaining nodes to be built
            &&
                this.nodeBuildQueue.hasNext()
        ) {
            final TreeNode<Split> node = this.nodeBuildQueue.next();
            // partition the data at the node
            List<Instances> partitions = node.getElement().split();
            // for each partition of data
            for(Instances partition : partitions) {
                // try to build a child node
                buildNode(partition, node);
            }
            trainTimeContracter.getTimer().lap();
        }
        if(getEstimateOwnPerformance()) {
            // todo put oob in contract
            trainResults = new ClassifierResults();
            for(Instance instance : oobTest) {
                Utils.addPrediction(this, instance, trainResults);
            }
        }
        trainTimeContracter.getTimer().disable();
        trainMemoryContracter.getWatcher().disable();
        if(getEstimateOwnPerformance()) {
            trainResults.setDetails(this, trainData);
        }
    }

    public Instances getOobTrain() {
        return oobTrain;
    }

    public Instances getOobTest() {
        return oobTest;
    }

    private TreeNode<Split> buildNode(Instances data, TreeNode<Split> parent) {
        // split the data into multiple partitions, housed in a Split object
        final Split split = splitter.buildSplit(data);
        // build a new node
        final TreeNode<Split> node = new BaseTreeNode<>(split);
        // set tree relationship
        node.setParent(parent);
        // check the stopping condition hasn't been hit
        final boolean stop = stoppingCondition.shouldStop(node);
        if(!stop) {
            // if not hit the stopping condition then add node to the build queue
            nodeBuildQueue.add(node);
        }
        return node;
    }

    @Override
    public void setTrainTimeLimit(final long time) {
        trainTimeContracter.setTimeLimit(time);
    }

    @Override
    public long getTrainTimeLimit() {
        return trainTimeContracter.getTimeLimit();
    }

    public StoppingCondition getStoppingCondition() {
        return stoppingCondition;
    }

    public ProximityTree setStoppingCondition(
        final StoppingCondition stoppingCondition) {
        Assert.assertNotNull(stoppingCondition);
        this.stoppingCondition = stoppingCondition;
        return this;
    }

    public Splitter getSplitter() {
        return splitter;
    }

    public ProximityTree setSplitter(final Splitter splitter) {
        Assert.assertNotNull(splitter);
        this.splitter = splitter;
        return this;
    }

    @Override
    public double[] distributionForInstance(final Instance instance) throws Exception {
        // enable resource monitors
        testMemoryContracter.getWatcher().resetAndEnable();
        testTimeContracter.getTimer().resetAndEnable();
        double[] distribution = new double[getNumClasses()];
        // start at the tree node
        TreeNode<Split> node = tree.getRoot();
        int index = -1;
        // traverse the tree downwards from root
        while(!node.isLeaf()) {
            // get the split at that node
            final Split split = node.getElement();
            // work out which branch to go to next
            index = split.getPartitionIndexFor(instance);
            final List<TreeNode<Split>> children = node.getChildren();
            // make this the next node to visit
            node = children.get(index);
        }
        // we've hit a leaf node by here
        // the index is the branch we're at AKA class value
        distribution[index]++;
        // disable the resource monitors
        testTimeContracter.getTimer().disable();
        testMemoryContracter.getWatcher().disable();
        return distribution;
    }

    @Override
    public long getTestTimeNanos() {
        return testTimeContracter.getTimer().getTimeNanos();
    }

    public MemoryContracter getTrainMemoryContracter() {
        return trainMemoryContracter;
    }

    public TimeContracter getTrainTimeContracter() {
        return trainTimeContracter;
    }
}