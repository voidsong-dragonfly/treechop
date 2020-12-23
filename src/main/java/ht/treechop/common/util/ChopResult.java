package ht.treechop.common.util;

import com.google.common.collect.Lists;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ChopResult {
    public static final ChopResult IGNORED = new ChopResult(Lists.newArrayList(), false);

    private final List<TreeBlock> blocks;
    private final boolean felling;

    public static final int MAX_NUM_FELLING_EFFECTS = 32;

    public ChopResult(List<TreeBlock> blocks, boolean felling) {
        this.blocks = blocks;
        this.felling = felling;
    }

    public ChopResult(List<TreeBlock> blocks) {
        this(blocks, false);
    }

    public ChopResult(World world, Collection<BlockPos> chopPositions, Collection<BlockPos> fellPositions) {
        this(
                Stream.of(chopPositions, fellPositions)
                        .flatMap(Collection::stream)
                        .map(pos -> new TreeBlock(world, pos, Blocks.AIR.getDefaultState()))
                        .collect(Collectors.toList()),
                true
        );
    }

    /**
     *  Applies the results of chopping to the world, spawning the appropriate drops.
     * - Chopped blocks: harvest by agent, change to chopped state
     * - Felled blocks: harvest by no one, change to felled state
     * - Chopped and felled blocks: harvest by agent, change to felled state
     */
    public void apply(BlockPos targetPos, PlayerEntity agent, ItemStack tool, boolean breakLeaves) {
        World world = agent.getEntityWorld();

        List<TreeBlock> logs = blocks.stream()
                .filter(treeBlock ->
                        ChopUtil.canChangeBlock(
                                treeBlock.getWorld(),
                                treeBlock.getPos(),
                                agent,
                                (treeBlock.wasChopped()) ? tool : ItemStack.EMPTY
                        )
                )
                .collect(Collectors.toList());

        List<TreeBlock> leaves = (felling && breakLeaves)
                ? ChopUtil.getTreeLeaves(
                                world,
                                logs.stream().map(TreeBlock::getPos).collect(Collectors.toList())
                        )
                        .stream()
                        .filter(pos -> ChopUtil.canChangeBlock(world, pos, agent))
                        .map(pos -> new TreeBlock(world, pos, Blocks.AIR.getDefaultState()))
                        .collect(Collectors.toList())
                : Lists.newArrayList();

        int numLogsAndLeaves = logs.size() + leaves.size();

        if (!world.isRemote() && !agent.isCreative()) {
            int fortune = EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE, tool);
            int silkTouch = EnchantmentHelper.getEnchantmentLevel(Enchantments.SILK_TOUCH, tool);

            AtomicInteger xpAccumulator = new AtomicInteger(0);

            PlayerEntity fakePlayer = (world instanceof ServerWorld)
                    ? FakePlayerFactory.getMinecraft((ServerWorld) world)
                    : agent;

            Stream.of(logs, leaves)
                    .flatMap(Collection::stream)
                    .forEach(treeBlock -> {
                        if (treeBlock.wasChopped()) {
                            harvestWorldBlock(agent, tool, xpAccumulator, fortune, silkTouch, treeBlock);
                        } else {
                            harvestWorldBlock(fakePlayer, ItemStack.EMPTY, xpAccumulator, 0, 0, treeBlock);
                        }
                    });

            ChopUtil.dropExperience(world, targetPos, xpAccumulator.get());
        }

        int numEffects = Math.min((int) Math.ceil(Math.sqrt(numLogsAndLeaves)), MAX_NUM_FELLING_EFFECTS) - 1;

        Collections.shuffle(logs);
        Collections.shuffle(leaves);
        int numLeavesEffects = (int) Math.ceil(numEffects * ((double) leaves.size() / (double) numLogsAndLeaves));
        int numLogsEffects = numEffects - numLeavesEffects;

        Stream.of(
                logs.stream().limit(numLogsEffects),
                leaves.stream().limit(numLeavesEffects)
        )
                .flatMap(a->a)
                .forEach(
                        treeBlock -> world.playEvent(
                                2001,
                                treeBlock.getPos(),
                                Block.getStateId(world.getBlockState(treeBlock.getPos()))
                        )
                );

        Stream.of(logs, leaves)
                .flatMap(Collection::stream)
                .forEach(
                        treeBlock -> treeBlock.getWorld().setBlockState(
                                treeBlock.getPos(),
                                treeBlock.getState(),
                                3
                        )
                );
    }

    private static void harvestWorldBlock(
            PlayerEntity agent,
            ItemStack tool,
            AtomicInteger totalXp,
            int fortune,
            int silkTouch,
            TreeBlock treeBlock
    ) {
        World world = treeBlock.getWorld();
        BlockPos pos = treeBlock.getPos();
        BlockState blockState = world.getBlockState(pos);
        blockState.getBlock().harvestBlock(
                world, agent, pos, blockState, world.getTileEntity(pos), tool
        );
        totalXp.getAndAdd(
                blockState.getExpDrop(world, pos, fortune, silkTouch)
        );
    }

}
