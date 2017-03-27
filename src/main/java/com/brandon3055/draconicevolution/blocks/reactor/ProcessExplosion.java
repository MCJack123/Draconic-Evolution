package com.brandon3055.draconicevolution.blocks.reactor;

import codechicken.lib.math.MathHelper;
import com.brandon3055.brandonscore.handlers.IProcess;
import com.brandon3055.brandonscore.lib.DelayedExecutor;
import com.brandon3055.brandonscore.lib.Vec3D;
import com.brandon3055.brandonscore.utils.SimplexNoise;
import com.brandon3055.brandonscore.utils.Utils;
import com.brandon3055.draconicevolution.DraconicEvolution;
import com.brandon3055.draconicevolution.lib.ExplosionHelper;
import com.brandon3055.draconicevolution.network.PacketExplosionFX;
import com.brandon3055.draconicevolution.utils.LogHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.common.network.NetworkRegistry;

import java.util.*;

/**
 * Created by brandon3055 on 11/03/2017.
 */
public class ProcessExplosion implements IProcess {

    public static DamageSource fusionExplosion = new DamageSource("damage.de.fusionExplode").setExplosion().setDamageBypassesArmor().setDamageIsAbsolute();

    /**
     * The origin of the explosion.
     */
    public final Vec3D origin;
    private final WorldServer world;
    private final int minimumDelay;
    public double[] angularResistance;
    public boolean isDead = false;
    public int radius = 0;
    public int maxRadius;
    public double circumference = 0;
    public double meanResistance = 0;
    private boolean calculationComplete = false;
    private boolean detonated = false;
    private long startTime = -1;
    private long calcWait = 0;
    /**
     * Set this to false to disable the laval dropped by the explosion.
     */
    public boolean lava = true;
    public HashSet<BlockPos> blocksToUpdate = new HashSet<>();
    public HashSet<HashSet<BlockPos>> destroyedBlocks = new HashSet<>();
    public HashSet<BlockPos> lavaPositions = new HashSet<>();

    public HashSet<BlockPos> destroyedCache = new HashSet<>();
    public HashSet<BlockPos> scannedCache = new HashSet<>();

    private IBlockState lavaState;

    /**
     * This process is responsible for handling some extremely large explosions as efficiently as possible.
     * The absolute max recommended radius is 500 (that's 1000 across) However this has only been tested on a high end system and
     * may crash on systems with less ram and processing power. Recommended max is 350 - 400;
     * @param origin The origin of the explosion.
     * @param radius The radius of the explosion (Note depending on terrain the actual destruction radius will be slightly less then this)
     * @param world The server world.
     * @param minimumDelayTime The minimum delay in seconds before detonation.
     *                         If the explosion calculation completes before this time is up the process will wait till this amount of time has based before detonating.
     *                         Use -1 for manual detonation.
     */
    public ProcessExplosion(BlockPos origin, int radius, WorldServer world, int minimumDelayTime) {
        this.origin = Vec3D.getCenter(origin);
        this.world = world;
        this.minimumDelay = minimumDelayTime;
        this.angularResistance = new double[121];
        Arrays.fill(angularResistance, 100);

        LogHelper.info("Explosion Calculation Started for " + radius + " Block radius detonation!");
        maxRadius = radius;

        lavaState = Blocks.FLOWING_LAVA.getDefaultState();
        LogHelper.dev(FluidRegistry.isFluidRegistered("pyrotheum"));
        if (FluidRegistry.isFluidRegistered("pyrotheum")) {
            Fluid pyro = FluidRegistry.getFluid("pyrotheum");
            if (pyro.canBePlacedInWorld()) {
                lavaState = pyro.getBlock().getDefaultState();
            }
        }
    }

    @Override
    public void updateProcess() {
        if (startTime == -1) {
            startTime = System.currentTimeMillis();
        }

        if (calcWait > 0) {
            calcWait--;
            return;
        }

        if (!calculationComplete) {
            long t = System.currentTimeMillis();
            updateCalculation();
            t = System.currentTimeMillis() - t;
            calcWait = t / 40;
            if (calcWait > 0) {
                LogHelper.dev("Explosion Calc loop took " + t + "ms! Waiting " + calcWait + " ticks before continuing");
            }
        }
        else if (minimumDelay == -1) {
            isDead = true;
        }
        else {
            if ((System.currentTimeMillis() - startTime) / 1000 >= minimumDelay) {
                detonate();
            }
        }
    }

    public void updateCalculation() {
        BlockPos originPos = origin.getPos();

        double maxCoreHeight = 20D * (maxRadius / 150D);

//        LogHelper.startTimer("Loop: " + radius);

        Vec3D posVec = new Vec3D();
        for (int x = originPos.getX() - radius; x < originPos.getX() + radius; x++) {
            for (int z = originPos.getZ() - radius; z < originPos.getZ() + radius; z++) {
                double dist = Utils.getDistanceAtoB(x, z, originPos.getX(), originPos.getZ());
                if (dist < radius && dist >= radius - 1) {
                    posVec.set(x + 0.5, origin.y, z + 0.5);
                    double radialAngle = getRadialAngle(posVec);
                    double radialResistance = getRadialResistance(radialAngle);
                    double angularLoad = (meanResistance / radialResistance) * 1;
                    double radialPos = 1D - (radius / (double) maxRadius);
                    double coreFalloff = Math.max(0, (radialPos - 0.8) * 5);
                    coreFalloff = 1 - ((1 - coreFalloff) * (1 - coreFalloff) * (1 - coreFalloff));
                    double coreHeight = coreFalloff * maxCoreHeight;
                    double edgeNoise = Math.max(0, (-radialPos + 0.2) * 5);
                    double edgeScatter = edgeNoise * world.rand.nextInt(10);
                    double sim = SimplexNoise.noise(x / 50D, z / 50D);
                    edgeNoise = 1 + (Math.abs(sim) * edgeNoise * 8);

                    double power = (10000 * radialPos * radialPos * radialPos * angularLoad * edgeNoise) + edgeScatter;
                    double heightUp = 20 + ((5D + (radius / 10D)) * angularLoad);
                    double heightDown = coreHeight + ((5D + (radius / 10D)) * angularLoad * (1 - coreFalloff));
                    heightDown += (Math.abs(sim) * 4) + world.rand.nextDouble();
                    heightUp += (Math.abs(sim) * 4) + world.rand.nextDouble();

                    double resist = trace(posVec.copy(), power/* * (1 + 8 * radialPos)*/, (int) heightUp * 3, 1, 0, 0);
                    resist += trace(posVec.subtract(0, 1, 0), power, (int) heightDown, -1, 0, 0);
                    resist *= 1 / angularLoad;

                    if (radialPos < 0.8) {
                        addRadialResistance(radialAngle, resist);
                    }
                }
            }
        }

        recalcResist();
        radius++;
        circumference = 2 * Math.PI * radius;

        destroyedBlocks.add(destroyedCache);
        destroyedCache = new HashSet<>();
        scannedCache = new HashSet<>();
//        LogHelper.stopTimer();

        if (radius >= maxRadius) {
            LogHelper.dev("Explosion Calculation Completed!");
            calculationComplete = true;
        }
    }


    //region Math Stuff

    private void recalcResist() {
        double total = 0;
        for (double resist : angularResistance) {
            total += resist;
        }

        meanResistance = total / angularResistance.length;
    }

    public double getRadialAngle(Vec3D pos) {
        double theta = Math.atan2(pos.x - origin.x, origin.z - pos.z);

        if (theta < 0.0) {
            theta += Math.PI * 2;
        }

        double angle = ((theta / (Math.PI * 2)) * (double) angularResistance.length);

//        if (MathHelper.floor(angle) + 1 % 45 == 0) {
//            angle++;
//            // /            angle += (world.rand.nextDouble() - 0.5) * 5;
////            LogHelper.dev(angle);
////            return angle;
//            if (angle < 0) {
//                angle += angularResistance.length;
//            }
//            else if (angle >= angularResistance.length) {
//                angle -= angularResistance.length;
//            }
//        }
//
//        if (MathHelper.floor(angle) - 1 % 45 == 0) {
//            angle--;
//            // /            angle += (world.rand.nextDouble() - 0.5) * 5;
////            LogHelper.dev(angle);
////            return angle;
//            if (angle < 0) {
//                angle += angularResistance.length;
//            }
//            else if (angle >= angularResistance.length) {
//                angle -= angularResistance.length;
//            }
//        }

        return angle;
    }

    public double getRadialResistance(double radialPos) {
        int min = MathHelper.floor(radialPos);
        if (min >= angularResistance.length) {
            min -= angularResistance.length;
        }
        int max = MathHelper.ceil(radialPos);
        if (max >= angularResistance.length) {
            max -= angularResistance.length;
        }

//        if (true) {
//            return (min % 2) * 1000;
//        }

        double delta = radialPos - min;

        return (angularResistance[min] * (1 - delta)) + (angularResistance[max] * delta);
    }

    public void addRadialResistance(double radialPos, double power) {
        int min = MathHelper.floor(radialPos);
        if (min >= angularResistance.length) {
            min -= angularResistance.length;
        }

        int max = MathHelper.ceil(radialPos);
        if (max >= angularResistance.length) {
            max -= angularResistance.length;
        }

        double delta = radialPos - min;
        angularResistance[min] += power * (1 - delta);
        angularResistance[max] += power * delta;
    }

    //endregion

    private double trace(Vec3D posVec, double power, int dist, int traceDir, double totalResist, int travel) {
        if (dist > 100) {
            dist = 100;
        }
        if (dist <= 0 || power <= 0 || posVec.y < 0 || posVec.y > 255) {
            return totalResist;
        }

        dist--;
        travel++;
        BlockPos pos = posVec.getPos();
        posVec.add(0, traceDir, 0);

        if (scannedCache.contains(pos) || destroyedCache.contains(pos)) {
            return trace(posVec, power, dist, traceDir, totalResist, travel);
        }

        double r = 1;

        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (!block.isAir(state, world, pos)) {
            Material mat = state.getMaterial();
            double effectivePower = (power / 10) * ((double) dist / (dist + travel));

            r = block.getExplosionResistance(null);

            if (effectivePower >= r) {
                destroyedCache.add(pos);
            }
            else if (mat == Material.WATER || mat == Material.LAVA) {
                if (effectivePower > 5) {
                    destroyedCache.add(pos);
                }
                else {
                    blocksToUpdate.add(pos);
                }
                r = 10;
            }
            else {
                if (mat == Material.LAVA || mat == Material.WATER || block instanceof BlockLiquid || block instanceof IFluidBlock || block instanceof BlockFalling) {
                    blocksToUpdate.add(pos);
                }
                scannedCache.add(pos);
            }

            if (r > 1000) {
                r = 1000;
            }
        }
        else {
            scannedCache.add(pos);
        }

        r = (r / radius) / travel;//?

        totalResist += r;
        power -= r;

        if (dist == 1 && traceDir == -1 && lava && world.rand.nextInt(250) == 0 && !world.isAirBlock(pos.down())) {
            dist = 0;
            if (destroyedCache.contains(pos)) {
                destroyedCache.remove(pos);
            }
//            world.setBlockState(pos, lavaState);
            lavaPositions.add(pos);
            blocksToUpdate.add(pos);
            scannedCache.add(pos);
        }

        return trace(posVec, power, dist, traceDir, totalResist, travel);
    }

    /**
     * @return true if explosion calculation is complete.
     */
    public boolean isCalculationComplete() {
        return calculationComplete;
    }

    /**
     * Call this once the explosion calculation has completed to manually detonate.
     * @return false if calculation is not yet complete or detonation has already occurred.
     */
    public boolean detonate() {
        if (!isCalculationComplete() || detonated) {
            return false;
        }

        long l = System.currentTimeMillis();

        LogHelper.dev("Removing Blocks!");
        LogHelper.startTimer("Adding Blocks For Removal");

        ExplosionHelper removalHelper = new ExplosionHelper(world);
        int i = 0;
        for (Collection<BlockPos> list : destroyedBlocks) {
            removalHelper.addBlocksForRemoval(list);
            i += list.size();
        }
        removalHelper.finish();

        LogHelper.stopTimer();
        LogHelper.startTimer("Adding Lava");

        for (BlockPos pos : lavaPositions) {
            world.setBlockState(pos, lavaState);
        }

        LogHelper.stopTimer();
        LogHelper.startTimer("Adding update Blocks");
        removalHelper.addBlocksForUpdate(blocksToUpdate);
        LogHelper.dev("Blocks Removed: " + i);
        LogHelper.stopTimer();

        isDead = true;
        detonated = true;

        final BlockPos pos = origin.getPos();
        PacketExplosionFX packet = new PacketExplosionFX(pos, radius);
        DraconicEvolution.network.sendToAllAround(packet, new NetworkRegistry.TargetPoint(world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), radius * 4));

        new DelayedExecutor(60) {
            @Override
            public void execute(Object[] args) {
                List<Entity> list = world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(pos, pos.add(1, 1, 1)).expand(radius * 2.5, radius * 2.5, radius * 2.5));

                for (Entity e : list) {
                    double dist = e.getDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    float dmg = 10000F * (1F - (float) (dist / (radius * 1.2D)));
                    e.attackEntityFrom(fusionExplosion, dmg);
                }
            }
        }.run();

        LogHelper.dev("Total explosion time: " + (System.currentTimeMillis() - l) / 1000D + "s");
        return true;
    }

    @Override
    public boolean isDead() {
        return isDead;
    }
}
