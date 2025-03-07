/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.operator.aggregation;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import io.trino.Session;
import io.trino.operator.GroupByIdBlock;
import io.trino.operator.MarkDistinctHash;
import io.trino.operator.PagesIndex;
import io.trino.operator.UpdateMemory;
import io.trino.operator.Work;
import io.trino.operator.aggregation.AggregationMetadata.AccumulatorStateDescriptor;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.RunLengthEncodedBlock;
import io.trino.spi.connector.SortOrder;
import io.trino.spi.function.WindowIndex;
import io.trino.spi.type.Type;
import io.trino.sql.gen.JoinCompiler;
import io.trino.type.BlockTypeOperators;

import javax.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static java.lang.Long.max;
import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;

public class GenericAccumulatorFactory
        implements AccumulatorFactory
{
    private final List<AccumulatorStateDescriptor> stateDescriptors;
    private final Constructor<? extends Accumulator> accumulatorConstructor;
    private final Constructor<? extends GroupedAccumulator> groupedAccumulatorConstructor;
    private final List<LambdaProvider> lambdaProviders;
    private final Optional<Integer> maskChannel;
    private final List<Integer> inputChannels;
    private final List<Type> sourceTypes;
    private final List<Integer> orderByChannels;
    private final List<SortOrder> orderings;
    private final boolean accumulatorHasRemoveInput;

    @Nullable
    private final JoinCompiler joinCompiler;
    @Nullable
    private final BlockTypeOperators blockTypeOperators;

    @Nullable
    private final Session session;
    private final boolean distinct;
    private final PagesIndex.Factory pagesIndexFactory;

    public GenericAccumulatorFactory(
            List<AccumulatorStateDescriptor> stateDescriptors,
            Constructor<? extends Accumulator> accumulatorConstructor,
            boolean accumulatorHasRemoveInput,
            Constructor<? extends GroupedAccumulator> groupedAccumulatorConstructor,
            List<LambdaProvider> lambdaProviders,
            List<Integer> inputChannels,
            Optional<Integer> maskChannel,
            List<Type> sourceTypes,
            List<Integer> orderByChannels,
            List<SortOrder> orderings,
            PagesIndex.Factory pagesIndexFactory,
            @Nullable JoinCompiler joinCompiler,
            @Nullable BlockTypeOperators blockTypeOperators,
            @Nullable Session session,
            boolean distinct)
    {
        this.stateDescriptors = requireNonNull(stateDescriptors, "stateDescriptors is null");
        this.accumulatorConstructor = requireNonNull(accumulatorConstructor, "accumulatorConstructor is null");
        this.accumulatorHasRemoveInput = accumulatorHasRemoveInput;
        this.groupedAccumulatorConstructor = requireNonNull(groupedAccumulatorConstructor, "groupedAccumulatorConstructor is null");
        this.lambdaProviders = ImmutableList.copyOf(requireNonNull(lambdaProviders, "lambdaProviders is null"));
        this.maskChannel = requireNonNull(maskChannel, "maskChannel is null");
        this.inputChannels = ImmutableList.copyOf(requireNonNull(inputChannels, "inputChannels is null"));
        this.sourceTypes = ImmutableList.copyOf(requireNonNull(sourceTypes, "sourceTypes is null"));
        this.orderByChannels = ImmutableList.copyOf(requireNonNull(orderByChannels, "orderByChannels is null"));
        this.orderings = ImmutableList.copyOf(requireNonNull(orderings, "orderings is null"));
        checkArgument(orderByChannels.isEmpty() || !isNull(pagesIndexFactory), "No pagesIndexFactory to process ordering");
        this.pagesIndexFactory = pagesIndexFactory;

        checkArgument(!distinct || !isNull(session) && !isNull(joinCompiler) && !isNull(blockTypeOperators), "joinCompiler, blockTypeOperators, and session needed when distinct is true");
        this.joinCompiler = joinCompiler;
        this.blockTypeOperators = blockTypeOperators;
        this.session = session;
        this.distinct = distinct;
    }

    @Override
    public List<Integer> getInputChannels()
    {
        return inputChannels;
    }

    @Override
    public boolean hasRemoveInput()
    {
        return accumulatorHasRemoveInput;
    }

    @Override
    public Accumulator createAccumulator()
    {
        Accumulator accumulator;

        if (distinct) {
            // channel 0 will contain the distinct mask
            accumulator = instantiateAccumulator(
                    inputChannels.stream()
                            .map(value -> value + 1)
                            .collect(Collectors.toList()),
                    Optional.of(0));

            List<Type> argumentTypes = inputChannels.stream()
                    .map(sourceTypes::get)
                    .collect(Collectors.toList());

            accumulator = new DistinctingAccumulator(accumulator, argumentTypes, inputChannels, maskChannel, session, joinCompiler, blockTypeOperators);
        }
        else {
            accumulator = instantiateAccumulator(inputChannels, maskChannel);
        }

        if (orderByChannels.isEmpty()) {
            return accumulator;
        }

        return new OrderingAccumulator(accumulator, sourceTypes, orderByChannels, orderings, pagesIndexFactory);
    }

    @Override
    public Accumulator createIntermediateAccumulator()
    {
        try {
            return accumulatorConstructor.newInstance(stateDescriptors, ImmutableList.of(), Optional.empty(), lambdaProviders);
        }
        catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public GroupedAccumulator createGroupedAccumulator()
    {
        GroupedAccumulator accumulator;

        if (distinct) {
            // channel 0 will contain the distinct mask
            accumulator = instantiateGroupedAccumulator(
                    inputChannels.stream()
                            .map(value -> value + 1)
                            .collect(Collectors.toList()),
                    Optional.of(0));

            List<Type> argumentTypes = new ArrayList<>();
            for (int input : inputChannels) {
                argumentTypes.add(sourceTypes.get(input));
            }

            accumulator = new DistinctingGroupedAccumulator(accumulator, argumentTypes, inputChannels, maskChannel, session, joinCompiler, blockTypeOperators);
        }
        else {
            accumulator = instantiateGroupedAccumulator(inputChannels, maskChannel);
        }

        if (orderByChannels.isEmpty()) {
            return accumulator;
        }

        return new OrderingGroupedAccumulator(accumulator, sourceTypes, orderByChannels, orderings, pagesIndexFactory);
    }

    @Override
    public GroupedAccumulator createGroupedIntermediateAccumulator()
    {
        try {
            return groupedAccumulatorConstructor.newInstance(stateDescriptors, ImmutableList.of(), Optional.empty(), lambdaProviders);
        }
        catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean hasOrderBy()
    {
        return !orderByChannels.isEmpty();
    }

    @Override
    public boolean hasDistinct()
    {
        return distinct;
    }

    private Accumulator instantiateAccumulator(List<Integer> inputs, Optional<Integer> mask)
    {
        try {
            return accumulatorConstructor.newInstance(stateDescriptors, inputs, mask, lambdaProviders);
        }
        catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private GroupedAccumulator instantiateGroupedAccumulator(List<Integer> inputs, Optional<Integer> mask)
    {
        try {
            return groupedAccumulatorConstructor.newInstance(stateDescriptors, inputs, mask, lambdaProviders);
        }
        catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static class DistinctingAccumulator
            implements Accumulator
    {
        private final Accumulator accumulator;
        private final MarkDistinctHash hash;
        private final int maskChannel;

        private DistinctingAccumulator(
                Accumulator accumulator,
                List<Type> inputTypes,
                List<Integer> inputs,
                Optional<Integer> maskChannel,
                Session session,
                JoinCompiler joinCompiler,
                BlockTypeOperators blockTypeOperators)
        {
            this.accumulator = requireNonNull(accumulator, "accumulator is null");
            this.maskChannel = requireNonNull(maskChannel, "maskChannel is null").orElse(-1);

            hash = new MarkDistinctHash(session, inputTypes, Ints.toArray(inputs), Optional.empty(), joinCompiler, blockTypeOperators, UpdateMemory.NOOP);
        }

        @Override
        public long getEstimatedSize()
        {
            return hash.getEstimatedSize() + accumulator.getEstimatedSize();
        }

        @Override
        public Type getFinalType()
        {
            return accumulator.getFinalType();
        }

        @Override
        public Type getIntermediateType()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Accumulator copy()
        {
            return accumulator.copy();
        }

        @Override
        public void addInput(Page page)
        {
            // 1. filter out positions based on mask, if present
            Page filtered;
            if (maskChannel >= 0) {
                filtered = filter(page, page.getBlock(maskChannel));
            }
            else {
                filtered = page;
            }

            if (filtered.getPositionCount() == 0) {
                return;
            }

            // 2. compute a distinct mask
            Work<Block> work = hash.markDistinctRows(filtered);
            checkState(work.process());
            Block distinctMask = work.getResult();

            // 3. feed a Page with a new mask to the underlying aggregation
            accumulator.addInput(filtered.prependColumn(distinctMask));
        }

        @Override
        public void addInput(WindowIndex index, List<Integer> channels, int startPosition, int endPosition)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeInput(WindowIndex index, List<Integer> channels, int startPosition, int endPosition)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addIntermediate(Block block)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void evaluateIntermediate(BlockBuilder blockBuilder)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void evaluateFinal(BlockBuilder blockBuilder)
        {
            accumulator.evaluateFinal(blockBuilder);
        }
    }

    private static Page filter(Page page, Block mask)
    {
        int positions = mask.getPositionCount();
        if (positions > 0 && mask instanceof RunLengthEncodedBlock) {
            // must have at least 1 position to be able to check the value at position 0
            if (!mask.isNull(0) && BOOLEAN.getBoolean(mask, 0)) {
                return page;
            }
            else {
                return page.getPositions(new int[0], 0, 0);
            }
        }
        boolean mayHaveNull = mask.mayHaveNull();
        int[] ids = new int[positions];
        int next = 0;
        for (int i = 0; i < ids.length; ++i) {
            boolean isNull = mayHaveNull && mask.isNull(i);
            if (!isNull && BOOLEAN.getBoolean(mask, i)) {
                ids[next++] = i;
            }
        }

        if (next == ids.length) {
            return page; // no rows were eliminated by the filter
        }
        return page.getPositions(ids, 0, next);
    }

    private static class DistinctingGroupedAccumulator
            implements GroupedAccumulator
    {
        private final GroupedAccumulator accumulator;
        private final MarkDistinctHash hash;
        private final int maskChannel;

        private DistinctingGroupedAccumulator(
                GroupedAccumulator accumulator,
                List<Type> inputTypes,
                List<Integer> inputChannels,
                Optional<Integer> maskChannel,
                Session session,
                JoinCompiler joinCompiler,
                BlockTypeOperators blockTypeOperators)
        {
            this.accumulator = requireNonNull(accumulator, "accumulator is null");
            this.maskChannel = requireNonNull(maskChannel, "maskChannel is null").orElse(-1);

            List<Type> types = ImmutableList.<Type>builder()
                    .add(BIGINT) // group id column
                    .addAll(inputTypes)
                    .build();

            int[] inputs = new int[inputChannels.size() + 1];
            inputs[0] = 0; // we'll use the first channel for group id column
            for (int i = 0; i < inputChannels.size(); i++) {
                inputs[i + 1] = inputChannels.get(i) + 1;
            }

            hash = new MarkDistinctHash(session, types, inputs, Optional.empty(), joinCompiler, blockTypeOperators, UpdateMemory.NOOP);
        }

        @Override
        public long getEstimatedSize()
        {
            return hash.getEstimatedSize() + accumulator.getEstimatedSize();
        }

        @Override
        public Type getFinalType()
        {
            return accumulator.getFinalType();
        }

        @Override
        public Type getIntermediateType()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addInput(GroupByIdBlock groupIdsBlock, Page page)
        {
            Page withGroup = page.prependColumn(groupIdsBlock);

            // 1. filter out positions based on mask, if present
            Page filtered;
            if (maskChannel >= 0) {
                filtered = filter(withGroup, withGroup.getBlock(maskChannel + 1)); // offset by one because of group id in column 0
            }
            else {
                filtered = withGroup;
            }

            // 2. compute a mask for the distinct rows (including the group id)
            Work<Block> work = hash.markDistinctRows(filtered);
            checkState(work.process());
            Block distinctMask = work.getResult();

            // 3. feed a Page with a new mask to the underlying aggregation
            GroupByIdBlock groupIds = new GroupByIdBlock(groupIdsBlock.getGroupCount(), filtered.getBlock(0));

            // drop the group id column and prepend the distinct mask column
            Block[] columns = new Block[filtered.getChannelCount()];
            columns[0] = distinctMask;
            for (int i = 1; i < filtered.getChannelCount(); i++) {
                columns[i] = filtered.getBlock(i);
            }

            accumulator.addInput(groupIds, new Page(filtered.getPositionCount(), columns));
        }

        @Override
        public void addIntermediate(GroupByIdBlock groupIdsBlock, Block block)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void evaluateIntermediate(int groupId, BlockBuilder output)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void evaluateFinal(int groupId, BlockBuilder output)
        {
            accumulator.evaluateFinal(groupId, output);
        }

        @Override
        public void prepareFinal()
        {
        }
    }

    private static class OrderingAccumulator
            implements Accumulator
    {
        private final Accumulator accumulator;
        private final List<Integer> orderByChannels;
        private final List<SortOrder> orderings;
        private final PagesIndex pagesIndex;

        private OrderingAccumulator(
                Accumulator accumulator,
                List<Type> aggregationSourceTypes,
                List<Integer> orderByChannels,
                List<SortOrder> orderings,
                PagesIndex.Factory pagesIndexFactory)
        {
            this.accumulator = requireNonNull(accumulator, "accumulator is null");
            this.orderByChannels = ImmutableList.copyOf(requireNonNull(orderByChannels, "orderByChannels is null"));
            this.orderings = ImmutableList.copyOf(requireNonNull(orderings, "orderings is null"));
            this.pagesIndex = pagesIndexFactory.newPagesIndex(aggregationSourceTypes, 10_000);
        }

        @Override
        public long getEstimatedSize()
        {
            return pagesIndex.getEstimatedSize().toBytes() + accumulator.getEstimatedSize();
        }

        @Override
        public Type getFinalType()
        {
            return accumulator.getFinalType();
        }

        @Override
        public Type getIntermediateType()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Accumulator copy()
        {
            return accumulator.copy();
        }

        @Override
        public void addInput(Page page)
        {
            pagesIndex.addPage(page);
        }

        @Override
        public void addInput(WindowIndex index, List<Integer> channels, int startPosition, int endPosition)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeInput(WindowIndex index, List<Integer> channels, int startPosition, int endPosition)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addIntermediate(Block block)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void evaluateIntermediate(BlockBuilder blockBuilder)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void evaluateFinal(BlockBuilder blockBuilder)
        {
            pagesIndex.sort(orderByChannels, orderings);
            Iterator<Page> pagesIterator = pagesIndex.getSortedPages();
            pagesIterator.forEachRemaining(accumulator::addInput);
            accumulator.evaluateFinal(blockBuilder);
        }
    }

    private static class OrderingGroupedAccumulator
            implements GroupedAccumulator
    {
        private final GroupedAccumulator accumulator;
        private final List<Integer> orderByChannels;
        private final List<SortOrder> orderings;
        private final PagesIndex pagesIndex;
        private long groupCount;

        private OrderingGroupedAccumulator(
                GroupedAccumulator accumulator,
                List<Type> aggregationSourceTypes,
                List<Integer> orderByChannels,
                List<SortOrder> orderings,
                PagesIndex.Factory pagesIndexFactory)
        {
            this.accumulator = requireNonNull(accumulator, "accumulator is null");
            requireNonNull(aggregationSourceTypes, "aggregationSourceTypes is null");
            this.orderByChannels = ImmutableList.copyOf(requireNonNull(orderByChannels, "orderByChannels is null"));
            this.orderings = ImmutableList.copyOf(requireNonNull(orderings, "orderings is null"));
            List<Type> pageIndexTypes = new ArrayList<>(aggregationSourceTypes);
            // Add group id column
            pageIndexTypes.add(BIGINT);
            this.pagesIndex = pagesIndexFactory.newPagesIndex(pageIndexTypes, 10_000);
            this.groupCount = 0;
        }

        @Override
        public long getEstimatedSize()
        {
            return pagesIndex.getEstimatedSize().toBytes() + accumulator.getEstimatedSize();
        }

        @Override
        public Type getFinalType()
        {
            return accumulator.getFinalType();
        }

        @Override
        public Type getIntermediateType()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addInput(GroupByIdBlock groupIdsBlock, Page page)
        {
            groupCount = max(groupCount, groupIdsBlock.getGroupCount());
            // Add group id block
            pagesIndex.addPage(page.appendColumn(groupIdsBlock));
        }

        @Override
        public void addIntermediate(GroupByIdBlock groupIdsBlock, Block block)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void evaluateIntermediate(int groupId, BlockBuilder output)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void evaluateFinal(int groupId, BlockBuilder output)
        {
            accumulator.evaluateFinal(groupId, output);
        }

        @Override
        public void prepareFinal()
        {
            pagesIndex.sort(orderByChannels, orderings);
            Iterator<Page> pagesIterator = pagesIndex.getSortedPages();
            pagesIterator.forEachRemaining(page -> {
                // The last channel of the page is the group id
                GroupByIdBlock groupIds = new GroupByIdBlock(groupCount, page.getBlock(page.getChannelCount() - 1));
                // We pass group id together with the other input channels to accumulator. Accumulator knows which input channels
                // to use. Since we did not change the order of original input channels, passing the group id is safe.
                accumulator.addInput(groupIds, page);
            });
        }
    }
}
