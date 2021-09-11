package me.jellysquid.mods.sodium.render.chunk.compile;

import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import me.jellysquid.mods.sodium.model.IndexBufferBuilder;
import me.jellysquid.mods.sodium.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.model.vertex.buffer.VertexBufferBuilder;
import me.jellysquid.mods.sodium.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.render.IndexedMesh;
import me.jellysquid.mods.sodium.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.render.chunk.data.BuiltChunkMesh;
import me.jellysquid.mods.sodium.render.chunk.data.ChunkRenderData;
import me.jellysquid.mods.sodium.render.chunk.format.ModelVertexSink;
import me.jellysquid.mods.sodium.render.chunk.passes.BlockRenderPass;
import me.jellysquid.mods.thingl.util.ElementRange;
import me.jellysquid.mods.thingl.util.NativeBuffer;
import net.minecraft.client.texture.Sprite;
import org.apache.commons.lang3.Validate;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * A collection of temporary buffers for each worker thread which will be used to build chunk meshes for given render
 * passes. This makes a best-effort attempt to pick a suitable size for each scratch buffer, but will never try to
 * shrink a buffer.
 */
public class ChunkBuildBuffers {
    private final Map<BlockRenderPass, ChunkModelBuilderImpl> builders;
    private final ChunkVertexType vertexType;

    private ChunkRenderData.Builder renderData;

    public ChunkBuildBuffers(ChunkVertexType vertexType) {
        this.builders = new Reference2ObjectArrayMap<>();
        this.vertexType = vertexType;
    }

    public Map<BlockRenderPass, BuiltChunkMesh> createMeshes() {
        Map<BlockRenderPass, BuiltChunkMesh> map = new Reference2ObjectOpenHashMap<>();

        for (Map.Entry<BlockRenderPass, ChunkModelBuilderImpl> entry : this.builders.entrySet()) {
            BlockRenderPass pass = entry.getKey();
            ChunkModelBuilderImpl builder = entry.getValue();

            map.put(pass, builder.createMesh());
        }

        return map;
    }

    public void release() {
        for (ChunkModelBuilderImpl builder : this.builders.values()) {
            builder.release();
        }

        this.builders.clear();
    }

    public ChunkModelBuilder getBuilder(BlockRenderPass pass) {
        ChunkModelBuilder builder = this.builders.get(pass);

        if (builder == null) {
            builder = this.createBuilder(pass);
        }

        return builder;
    }

    private ChunkModelBuilder createBuilder(BlockRenderPass pass) {
        Validate.notNull(this.renderData, "Render data container not attached");

        IndexBufferBuilder[] indexBufferBuilders = new IndexBufferBuilder[ModelQuadFacing.COUNT];

        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            indexBufferBuilders[facing] = new IndexBufferBuilder(2048);
        }

        VertexBufferBuilder vertexBufferBuilder = new VertexBufferBuilder(this.vertexType.getBufferVertexFormat(),
                8192 * this.vertexType.getBufferVertexFormat().getStride());

        ChunkModelBuilderImpl builder = new ChunkModelBuilderImpl(indexBufferBuilders, vertexBufferBuilder,
                this.vertexType, this.renderData);

        this.builders.put(pass, builder);

        return builder;
    }

    public void prepare(ChunkRenderData.Builder renderData) {
        this.renderData = renderData;
    }

    private static class ChunkModelBuilderImpl implements ChunkModelBuilder {
        private final IndexBufferBuilder[] indexBufferBuilders;
        private final VertexBufferBuilder vertexBufferBuilder;

        private final ModelVertexSink vertexSink;

        private final ChunkRenderData.Builder renderData;

        public ChunkModelBuilderImpl(IndexBufferBuilder[] indexBufferBuilders, VertexBufferBuilder vertexBufferBuilder,
                                     ChunkVertexType vertexType, ChunkRenderData.Builder renderData) {
            this.indexBufferBuilders = indexBufferBuilders;
            this.vertexBufferBuilder = vertexBufferBuilder;
            this.vertexSink = vertexType.createBufferWriter(this.vertexBufferBuilder);
            this.renderData = renderData;
        }

        @Override
        public ModelVertexSink getVertexSink() {
            return this.vertexSink;
        }

        @Override
        public IndexBufferBuilder getIndexSink(ModelQuadFacing facing) {
            return this.indexBufferBuilders[facing.ordinal()];
        }

        @Override
        public void addSprite(Sprite sprite) {
            this.renderData.addSprite(sprite);
        }

        /**
         * Creates immutable baked chunk meshes from all non-empty scratch buffers. This is used after all blocks
         * have been rendered to pass the finished meshes over to the graphics card. This function can be called multiple
         * times to return multiple copies.
         */
        public BuiltChunkMesh createMesh() {
            NativeBuffer vertexBuffer = this.vertexBufferBuilder.pop();

            if (vertexBuffer == null) {
                return null;
            }

            IndexBufferBuilder.Result[] indexBuffers = Arrays.stream(this.indexBufferBuilders)
                    .map(IndexBufferBuilder::pop)
                    .toArray(IndexBufferBuilder.Result[]::new);

            NativeBuffer indexBuffer = new NativeBuffer(Arrays.stream(indexBuffers)
                    .filter(Objects::nonNull)
                    .mapToInt(IndexBufferBuilder.Result::getByteSize)
                    .sum());

            int indexPointer = 0;

            Map<ModelQuadFacing, ElementRange> slices = new EnumMap<>(ModelQuadFacing.class);

            for (ModelQuadFacing facing : ModelQuadFacing.VALUES) {
                IndexBufferBuilder.Result indices = indexBuffers[facing.ordinal()];

                if (indices == null) {
                    continue;
                }

                slices.put(facing,
                        new ElementRange(indexPointer, indices.getCount(), indices.getFormat(), indices.getBaseVertex()));

                indexPointer = indices.writeTo(indexPointer, indexBuffer.getDirectBuffer());
            }

            return new BuiltChunkMesh(new IndexedMesh(vertexBuffer, indexBuffer), slices);
        }

        public void release() {
            for (IndexBufferBuilder indexBufferBuilder : this.indexBufferBuilders) {
                indexBufferBuilder.destroy();
            }

            this.vertexBufferBuilder.destroy();
        }
    }
}
