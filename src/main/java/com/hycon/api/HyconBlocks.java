package com.hycon.api;

/**
 * Blocks for a 3D replay, a tile at a time: the replay names this source and a key ({@link PanelData.Replay#blockSource}),
 * and the browser asks for the tiles around the camera as it plays, so only a small area is ever sent and the page
 * itself stays small. Register with {@link HyconApi#registerBlockSource}. Since API version 14.
 *
 * <p>{@link #tile} runs on a Hycon web thread for each tile the browser asks for: read thread-safe data only.
 */
public interface HyconBlocks {

    /** Receives a tile's blocks. */
    interface Tile {
        /** One visible block: its position and colour ("#rrggbb"). Blocks outside the tile are ignored. */
        void block(int x, int y, int z, String colour);
    }

    /** Lowest account level that may fetch tiles; one of {@link HyconApi#VIEWER} etc. */
    default int minLevel() {
        return HyconApi.VIEWER;
    }

    /**
     * Fills in the blocks of the replay {@code key} in columns {@code x0 <= x < x0 + size}, {@code z0 <= z < z0 + size},
     * from {@code yMin} to {@code yMax}: the browser asks for a band around the player, so the ground under them only
     * comes once they go down there.
     */
    void tile(String key, int x0, int z0, int size, int yMin, int yMax, Tile out);
}
