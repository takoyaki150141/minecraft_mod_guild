package dev.guildquests.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * Terrain colours for the land map. Sampling happens on the server because the client
 * cannot be assumed to have every chunk in the purchase radius loaded.
 */
public final class LandTerrain {
    /** 0 means "no data"; the client then draws a neutral cell. */
    public static final int UNKNOWN = 0;
    private static final int SAMPLES = 4;

    private LandTerrain() { }

    /**
     * Averages the surface colour of each chunk around {@code center}.
     * Index order matches the land view: row-major, north to south, west to east.
     */
    public static int[] sample(ServerLevel level, ChunkPos center, int radius) {
        int clamped = Math.max(1, Math.min(8, radius));
        int side = clamped * 2 + 1;
        int[] colors = new int[side * side];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dz = -clamped; dz <= clamped; dz++) {
            for (int dx = -clamped; dx <= clamped; dx++) {
                int chunkX = center.x + dx;
                int chunkZ = center.z + dz;
                int index = (dz + clamped) * side + (dx + clamped);
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) continue;

                long red = 0;
                long green = 0;
                long blue = 0;
                int count = 0;
                int step = 16 / SAMPLES;
                for (int sx = 0; sx < SAMPLES; sx++) {
                    for (int sz = 0; sz < SAMPLES; sz++) {
                        int x = chunkX * 16 + sx * step + step / 2;
                        int z = chunkZ * 16 + sz * step + step / 2;
                        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        pos.set(x, Math.max(level.getMinBuildHeight(), y - 1), z);
                        BlockState state = level.getBlockState(pos);
                        MapColor mapColor = state.getMapColor(level, pos);
                        if (mapColor == null) continue;
                        int rgb = mapColor.col;
                        red += (rgb >> 16) & 0xFF;
                        green += (rgb >> 8) & 0xFF;
                        blue += rgb & 0xFF;
                        count++;
                    }
                }
                if (count > 0) {
                    colors[index] = ((int) (red / count) << 16)
                            | ((int) (green / count) << 8)
                            | (int) (blue / count);
                }
            }
        }
        return colors;
    }
}
