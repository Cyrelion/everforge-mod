package de.everforge.mod.server.worldedit;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI;

import java.util.List;

/**
 * OpenPAC-backed WorldEdit extent.
 *
 * Protects:
 * - block mutations
 * - biome mutations
 * - entity creation
 * - entity enumeration used by WorldEdit entity removal commands
 *
 * WorldEdit's /butcher and /remove create an EditSession and obtain the
 * candidate entities through EditSession#getEntities(...). By filtering those
 * lists here, entities in chunks where OpenPAC denies access never reach
 * EntityVisitor and therefore cannot be removed.
 */
public final class OpenPacClaimExtent extends AbstractDelegateExtent {
    private final ServerPlayer player;
    private final ResourceLocation dimension;
    private final IChunkProtectionAPI protection;

    private int cachedChunkX = Integer.MIN_VALUE;
    private int cachedChunkZ = Integer.MIN_VALUE;
    private boolean cachedAccess;

    public enum EditSessionStage {
        BEFORE_CHANGE,
        BEFORE_HISTORY
    }

    public OpenPacClaimExtent(
            Extent extent,
            ServerPlayer player,
            ResourceLocation dimension,
            IChunkProtectionAPI protection,
            EditSessionStage stage
    ) {
        super(extent);
        this.player = player;
        this.dimension = dimension;
        this.protection = protection;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(
            BlockVector3 location,
            T block
    ) throws WorldEditException {
        if (!hasAccess(location.x() >> 4, location.z() >> 4)) {
            return false;
        }
        return super.setBlock(location, block);
    }

    @Override
    public boolean setBiome(BlockVector3 position, BiomeType biome) {
        if (!hasAccess(position.x() >> 4, position.z() >> 4)) {
            return false;
        }
        return super.setBiome(position, biome);
    }

    @Override
    public Entity createEntity(Location location, BaseEntity entity) {
        if (!hasAccess(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return null;
        }
        return super.createEntity(location, entity);
    }

    @Override
    public List<? extends Entity> getEntities() {
        return super.getEntities().stream()
                .filter(this::hasEntityAccess)
                .toList();
    }

    @Override
    public List<? extends Entity> getEntities(Region region) {
        return super.getEntities(region).stream()
                .filter(this::hasEntityAccess)
                .toList();
    }

    private boolean hasEntityAccess(Entity entity) {
        Location location = entity.getLocation();
        return hasAccess(
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4
        );
    }

    private boolean hasAccess(int chunkX, int chunkZ) {
        if (chunkX == cachedChunkX && chunkZ == cachedChunkZ) {
            return cachedAccess;
        }

        cachedChunkX = chunkX;
        cachedChunkZ = chunkZ;

        cachedAccess = protection.hasChunkAccess(
                player,
                dimension,
                chunkX,
                chunkZ
        );

        return cachedAccess;
    }
}
