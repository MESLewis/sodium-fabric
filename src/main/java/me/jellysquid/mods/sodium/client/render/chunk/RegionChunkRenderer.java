package me.jellysquid.mods.sodium.client.render.chunk;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.systems.RenderSystem;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.buffer.*;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.DrawCommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.sync.GlFence;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlIndexType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlTessellation;
import me.jellysquid.mods.sodium.client.gl.tessellation.TessellationBinding;
import me.jellysquid.mods.sodium.client.gl.util.ElementRange;
import me.jellysquid.mods.sodium.client.gl.util.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.chunk.data.ChunkRenderBounds;
import me.jellysquid.mods.sodium.client.render.chunk.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ComputeShaderInterface;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

public class RegionChunkRenderer extends ShaderChunkRenderer {
    private final MultiDrawBatch batch;
    private final GlVertexAttributeBinding[] vertexAttributeBindings;

    private final GlMutableBuffer chunkInfoBuffer;
    private final GlMutableBuffer batchSubData;
    private final ByteBuffer batchSubDataBuffer;
    private final boolean isBlockFaceCullingEnabled = SodiumClientMod.options().advanced.useBlockFaceCulling;

    public RegionChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);

        this.vertexAttributeBindings = new GlVertexAttributeBinding[] {
                new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID,
                        this.vertexFormat.getAttribute(ChunkMeshAttribute.POSITION_ID)),
                new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_COLOR,
                        this.vertexFormat.getAttribute(ChunkMeshAttribute.COLOR)),
                new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE,
                        this.vertexFormat.getAttribute(ChunkMeshAttribute.BLOCK_TEXTURE)),
                new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE,
                        this.vertexFormat.getAttribute(ChunkMeshAttribute.LIGHT_TEXTURE))
        };

        try (CommandList commandList = device.createCommandList()) {
            this.chunkInfoBuffer = commandList.createMutableBuffer();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                commandList.uploadData(this.chunkInfoBuffer, createChunkInfoBuffer(stack), GlBufferUsage.STATIC_DRAW);
            }

            this.batchSubData = commandList.createMutableBuffer();
            batchSubDataBuffer = ByteBuffer.allocate(RenderRegion.REGION_SIZE * 3 * 4);
        }

        this.batch = MultiDrawBatch.create(ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE);
    }

    private static ByteBuffer createChunkInfoBuffer(MemoryStack stack) {
        int stride = 4 * 4;
        ByteBuffer data = stack.malloc(RenderRegion.REGION_SIZE * stride);

        for (int x = 0; x < RenderRegion.REGION_WIDTH; x++) {
            for (int y = 0; y < RenderRegion.REGION_HEIGHT; y++) {
                for (int z = 0; z < RenderRegion.REGION_LENGTH; z++) {
                    int i = RenderRegion.getChunkIndex(x, y, z) * stride;

                    data.putFloat(i + 0, x * 16.0f);
                    data.putFloat(i + 4, y * 16.0f);
                    data.putFloat(i + 8, z * 16.0f);
                }
            }
        }

        return data;
    }

    @Override
    public void render(MatrixStack matrixStack, CommandList commandList,
                       ChunkRenderList list, BlockRenderPass pass,
                       ChunkCameraContext camera) {
        super.begin(pass);

        ChunkShaderInterface shader = this.activeProgram.getInterface();

        shader.setProjectionMatrix(RenderSystem.getProjectionMatrix());
        shader.setDrawUniforms(this.chunkInfoBuffer);

        for (Map.Entry<RenderRegion, List<RenderSection>> entry : sortedRegions(list, pass.isTranslucent())) {
            RenderRegion region = entry.getKey();
            List<RenderSection> regionSections = entry.getValue();

            if (!buildDrawBatches(regionSections, pass, camera)) {
                continue;
            }

            //TODO Clean up, fix lag spikes, fix water
            GlProgram<ComputeShaderInterface> compute = shader.getCompute();
            if (compute != null) {
                super.end();
                compute.bind();

                if (!regionSections.isEmpty()) {
                    RenderRegion.RenderRegionArenas arenas = region.getArenas();

                    float x = getCameraTranslation(region.getOriginX(), camera.blockX, camera.deltaX);
                    float y = getCameraTranslation(region.getOriginY(), camera.blockY, camera.deltaY);
                    float z = getCameraTranslation(region.getOriginZ(), camera.blockZ, camera.deltaZ);

                    Matrix4f matrix = matrixStack.peek()
                            .getModel()
                            .copy();
                    matrix.multiplyByTranslation(x, y, z);

                    compute.getInterface().setModelViewMatrix(matrix);
                    compute.getInterface().setDrawUniforms(this.chunkInfoBuffer);
                    compute.getInterface().uniformModelScale.setFloat(vertexType.getModelScale());
                    compute.getInterface().uniformModelOffset.setFloat(vertexType.getModelOffset());


                    ArrayList<Integer> multiDrawList = new ArrayList<>();
                    ArrayList<Integer> subDataList = new ArrayList<>();
                    int chunkCount = 0;
                    PointerBuffer pointerBuffer = batch.getPointerBuffer();
                    IntBuffer countBuffer = batch.getCountBuffer();
                    IntBuffer baseVertexBuffer = batch.getBaseVertexBuffer();

                    int lastBaseVertexOffset = baseVertexBuffer.get(0);
                    int subDataCount = 0;
                    int totalSubDataCount = 0;

                    int pointer;
                    int count;
                    int baseVertex;
                    while(countBuffer.hasRemaining()) {
                        pointer = (int) (pointerBuffer.get() / 12); //Buffer is referencing bytes, so we divide by 12 to be referencing the array of structs
                        count = countBuffer.get() / 3; //3 verticies per array entry.
                        baseVertex = baseVertexBuffer.get(); //Already referencing array entries

                        multiDrawList.add(pointer); //IndexOffset
                        multiDrawList.add(count); //IndexLength
                        multiDrawList.add(baseVertex); //VertexOffset

                        if(baseVertex != lastBaseVertexOffset) {
                            lastBaseVertexOffset = baseVertex;

                            subDataList.add(totalSubDataCount);
                            subDataList.add(subDataCount);
                            totalSubDataCount += subDataCount;
                            subDataCount = 0;
                            chunkCount++;
                        }
                        subDataCount++;
                    }
                    subDataList.add(totalSubDataCount);
                    subDataList.add(subDataCount);
                    chunkCount++;

                    int buf = glGenBuffers();
                    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf);
                    glBufferData(GL_SHADER_STORAGE_BUFFER, multiDrawList.stream().mapToInt(i -> i).toArray(), GL_DYNAMIC_DRAW);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, buf);
                    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

                    buf = glGenBuffers();
                    glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf);
                    glBufferData(GL_SHADER_STORAGE_BUFFER, subDataList.stream().mapToInt(i -> i).toArray(), GL_DYNAMIC_DRAW);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, buf);
                    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

//                    GlMutableBuffer temp = commandList.createMutableBuffer();
//                    commandList.uploadData(temp, batchSubDataBuffer, GlBufferUsage.DYNAMIC_DRAW);
//                    commandList.bindBuffer(GlBufferTarget.SHADER_STORAGE_BUFFER, batchSubData);

                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, arenas.vertexBuffers.getBufferObject().handle());
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, arenas.indexBuffers.getBufferObject().handle());
                    glDispatchCompute(chunkCount, 1, 1);
//                    glDispatchCompute(1, 1, 1);
                }
//            }
//                GlFence fence = commandList.createFence();
//                fence.sync();
//                if(!fence.isCompleted()) {
//                    System.out.println("COMPUTE TOO SLOW");
//                }
//                glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
//                glClientWaitSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0, 1000000);
                compute.unbind();
                glMemoryBarrier(GL_ALL_BARRIER_BITS);
                super.begin(pass);
            }
            //END TODO

            GlTessellation tessellation = this.createTessellationForRegion(commandList, region.getArenas(), pass);
            this.setModelMatrixUniforms(shader, matrixStack, region, camera);
            executeDrawBatches(commandList, tessellation);
        }
        super.end();
    }

    private boolean buildDrawBatches(List<RenderSection> sections, BlockRenderPass pass, ChunkCameraContext camera) {
        batch.begin();

        for (RenderSection render : sortedChunks(sections, pass.isTranslucent())) {
            ChunkGraphicsState state = render.getGraphicsState(pass);

            if (state == null) {
                continue;
            }

            ChunkRenderBounds bounds = render.getBounds();

            long indexOffset = state.getIndexSegment()
                    .getOffset();

            int baseVertex = state.getVertexSegment()
                    .getOffset() / this.vertexFormat.getStride();

            this.addDrawCall(state.getModelPart(ModelQuadFacing.UNASSIGNED), indexOffset, baseVertex);

            if (this.isBlockFaceCullingEnabled) {
                if (camera.posY > bounds.y1) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.UP), indexOffset, baseVertex);
                }

                if (camera.posY < bounds.y2) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.DOWN), indexOffset, baseVertex);
                }

                if (camera.posX > bounds.x1) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.EAST), indexOffset, baseVertex);
                }

                if (camera.posX < bounds.x2) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.WEST), indexOffset, baseVertex);
                }

                if (camera.posZ > bounds.z1) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.SOUTH), indexOffset, baseVertex);
                }

                if (camera.posZ < bounds.z2) {
                    this.addDrawCall(state.getModelPart(ModelQuadFacing.NORTH), indexOffset, baseVertex);
                }
            } else {
                for (ModelQuadFacing facing : ModelQuadFacing.DIRECTIONS) {
                    this.addDrawCall(state.getModelPart(facing), indexOffset, baseVertex);
                }
            }
        }

        batch.end();
        return !batch.isEmpty();
    }

    private GlTessellation createTessellationForRegion(CommandList commandList, RenderRegion.RenderRegionArenas arenas, BlockRenderPass pass) {
        GlTessellation tessellation = arenas.getTessellation(pass);

        if (tessellation == null) {
            arenas.setTessellation(pass, tessellation = this.createRegionTessellation(commandList, arenas));
        }

        return tessellation;
    }

    private void executeDrawBatches(CommandList commandList, GlTessellation tessellation) {
        try (DrawCommandList drawCommandList = commandList.beginTessellating(tessellation)) {
            drawCommandList.multiDrawElementsBaseVertex(batch.getPointerBuffer(), batch.getCountBuffer(), batch.getBaseVertexBuffer(), GlIndexType.UNSIGNED_INT);
        }
    }

    private void setModelMatrixUniforms(ChunkShaderInterface shader, MatrixStack matrixStack, RenderRegion region, ChunkCameraContext camera) {
        float x = getCameraTranslation(region.getOriginX(), camera.blockX, camera.deltaX);
        float y = getCameraTranslation(region.getOriginY(), camera.blockY, camera.deltaY);
        float z = getCameraTranslation(region.getOriginZ(), camera.blockZ, camera.deltaZ);

        Matrix4f matrix = matrixStack.peek()
                .getModel()
                .copy();
        matrix.multiplyByTranslation(x, y, z);

        shader.setModelViewMatrix(matrix);
    }

    private void addDrawCall(ElementRange part, long baseIndexPointer, int baseVertexIndex) {
        if (part != null) {
            batch.add(baseIndexPointer + part.elementPointer(), part.elementCount(), baseVertexIndex + part.baseVertex());
        }
    }

    private GlTessellation createRegionTessellation(CommandList commandList, RenderRegion.RenderRegionArenas arenas) {
        return commandList.createTessellation(GlPrimitiveType.TRIANGLES, new TessellationBinding[] {
                TessellationBinding.forVertexBuffer(arenas.vertexBuffers.getBufferObject(), this.vertexAttributeBindings),
                TessellationBinding.forElementBuffer(arenas.indexBuffers.getBufferObject())
        });
    }

    @Override
    public void delete() {
        super.delete();

        batch.delete();

        RenderDevice.INSTANCE.createCommandList()
                .deleteBuffer(this.chunkInfoBuffer);
    }

    private static Iterable<Map.Entry<RenderRegion, List<RenderSection>>> sortedRegions(ChunkRenderList list, boolean translucent) {
        return list.sorted(translucent);
    }

    private static Iterable<RenderSection> sortedChunks(List<RenderSection> chunks, boolean translucent) {
        return translucent ? Lists.reverse(chunks) : chunks;
    }

    private static float getCameraTranslation(int chunkBlockPos, int cameraBlockPos, float cameraPos) {
        return (chunkBlockPos - cameraBlockPos) - cameraPos;
    }
}
